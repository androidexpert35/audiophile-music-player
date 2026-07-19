// ────────────────────────────────────────────────────────────────────────────
// ffmpeg_bridge.cpp
//
// JNI bridge between the Audiophile Kotlin layer (FFmpegDecoder.kt) and the
// vendored FFmpeg static libraries. One decoder = one native session handle
// (opaque `jlong` on the Kotlin side).
//
// Design constraints (mirrored in the accompanying Kotlin KDoc):
//
//   1. Single-owner model. Every method on a handle MUST be called from the
//      same thread (the engine's audio thread). No internal locking.
//
//   2. Source-depth-preserving output format selection:
//          src ≤ 16-bit (or lossy / FLTP with no bits_per_raw_sample)
//                                →  AV_SAMPLE_FMT_S16  / ENCODING_PCM_16BIT
//          src 17–24-bit         →  AV_SAMPLE_FMT_S32  / ENCODING_PCM_32BIT
//                                   (24-bit zero-padded into S32 — Android's
//                                    ENCODING_PCM_24BIT_PACKED is advisory and
//                                    inconsistently aligned on vendor HALs)
//          src 25–32-bit integer →  AV_SAMPLE_FMT_S32  / ENCODING_PCM_32BIT
//          src 32-bit float      →  AV_SAMPLE_FMT_FLT  / ENCODING_PCM_FLOAT
//
//   3. SwrContext is allocated ONLY when the decoder's native sample format
//      does not already match the chosen packed interleaved target. For
//      packed-S16/S32/FLT sources the hot path bypasses swresample entirely
//      so the bytes that leave the decoder are the bytes that reach
//      AudioTrack — no volume processing, no dithering, no resampling.
//
//   4. DSD Tier-3 PCM fallback: soxr VHQ (precision=33, linear-phase) is used
//      through FFmpeg's lavfi `aresample=osr=88200:resampler=soxr:precision=33`
//      filter stage inside the DSD-prep graph. No standalone soxr C-API calls
//      are made in this file. The full pipeline is:
//        volume/lowpass/alimiter → aformat=flt → aresample(soxr,88200Hz)
//      Fallback: if the lavfi graph build fails, libswresample sinc is used.
//
//   5. Error semantics:
//        - `nativeOpen / nativeSeek`    → throw AudiophileDecoderException
//          (slow path, caller reacts in Kotlin).
//        - `nativeReadNextBuffer`       → returns negative AVERROR mapped to
//          stable Kotlin sentinels; NEVER throws (hot path).
// ────────────────────────────────────────────────────────────────────────────

#include <jni.h>
#include <android/log.h>
#include <cerrno>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstdio>
#include <cstring>
#include <memory>
#include <new>
#include <string>
#include <vector>

#include "cpu_affinity_policy.h"   // DecodeLoad + audio-thread pinning

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavfilter/avfilter.h>
#include <libavfilter/buffersink.h>
#include <libavfilter/buffersrc.h>
#include <libavutil/channel_layout.h>
#include <libavutil/opt.h>
#include <libavutil/samplefmt.h>
#include <libswresample/swresample.h>
}

// libsoxr is integrated via FFmpeg's lavfi resampler (resampler=soxr:precision=33).
// DSD decimation and hi-res resampling inside ffmpeg_bridge use the soxr lavfi
// path; the standalone soxr C API (soxr_bridge.cpp / SoxResamplerStage) handles
// only the Kotlin-driven standalone upsampling stage.

#define LOG_TAG "AudiophileNative"
#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ── Kotlin-facing stable sentinels (keep in sync with FFmpegDecoder.kt) ─────
static constexpr int READ_RECOVERABLE_ERROR = -1;

// ── PCM encoding IDs mirror android.media.AudioFormat constants ─────────────
static constexpr int ENCODING_PCM_16BIT = 2;
static constexpr int ENCODING_PCM_FLOAT = 4;
static constexpr int ENCODING_PCM_32BIT = 22;

// ─────────────────────────────────────────────────────────────────────────────
// Decoder session state.
//
// One allocation per Kotlin FFmpegDecoder instance; returned as an opaque
// jlong. The Kotlin side is responsible for calling nativeClose exactly once.
// ─────────────────────────────────────────────────────────────────────────────
struct Session {
    AVFormatContext *fmt_ctx       = nullptr;
    AVCodecContext  *codec_ctx     = nullptr;
    SwrContext      *swr_ctx       = nullptr;   // null when pass-through path is viable
    AVPacket        *packet        = nullptr;
    AVFrame         *frame         = nullptr;

    int              audio_stream_index = -1;

    // Chosen output shape. Input is reinterpreted unchanged into these when
    // swr_ctx is null; otherwise swresample converts into this shape.
    AVSampleFormat   out_sample_fmt = AV_SAMPLE_FMT_NONE;
    int              out_bytes_per_sample = 0;     // per channel
    int              out_sample_rate = 0;
    int              out_channels = 0;
    int              out_android_encoding = 0;     // AudioFormat.ENCODING_PCM_*

    // Leftover PCM bytes produced by the last decode cycle that did not fit
    // into the previous Java ByteArray. Drained before the next av_read_frame.
    uint8_t         *spill_buf = nullptr;
    int              spill_len = 0;
    int              spill_cap = 0;

    // Reported source bit-depth — for telemetry. Equal to the decoder's
    // bits_per_raw_sample when available, otherwise derived from sample_fmt.
    int              source_bit_depth = 0;

    // AVCodecContext->bit_rate at open time (or container-level fallback).
    int64_t          bitrate_bps = 0;

    // DSD sessions bypass FFmpeg PCM decode entirely. We read AVPacket payloads,
    // normalize them to MSB-first, and expose the raw one-bit stream to Kotlin.
    bool             is_dsd = false;
    int              dsd_rate_multiplier = 0;
    std::string      container_name;

    // Exact raw-DSD seek target retained after av_seek_frame() lands on the
    // preceding packet boundary. The first post-seek packet is trimmed to this
    // microsecond position before any bytes reach the USB/DoP pipeline.
    int64_t          exact_dsd_seek_target_us = AV_NOPTS_VALUE;

    // Set to `true` when the source file is DSD but the session is decoded to
    // high-res PCM (Tier-3 fallback) — the telemetry layer still shows the
    // DSD heritage to the user while flagging that the carrier is resampled.
    bool             is_resampled_dsd = false;

    // ReplayGain track gain extracted from the file's AVDictionary at open time.
    // Resolved lazily via nativeGetReplayGainDb only when the Kotlin layer is
    // about to build the Hi-Res Dynamic Remaster chain. Ordinary playback,
    // lossy SUE, force-48k, and DSD paths must not trigger metadata scans or
    // ReplayGain logs.
    float            replaygain_db = -3.0f;   // = REMASTER_DEFAULT_GAIN_DB (assume peak = 1.0)
    bool             replaygain_resolved = false;

    // ── Level-3 DSD-prep lavfi filter graph ───────────────────────────────────
    // Non-null only when is_resampled_dsd == true AND the graph was built
    // successfully in nativeOpen.
    //
    // The graph applies the full pipeline including soxr VHQ decimation to
    // 88 200 Hz entirely inside lavfi — no manual soxr C-API calls needed:
    //   1. volume=10dB      — SACD Scarlet Book headroom restore (+4 dB above base)
    //   2. lowpass=28kHz    — Butterworth biquad strips ultrasonic DSD noise
    //                         BEFORE soxr decimation to prevent aliasing.
    //   3. alimiter         — Brickwall at −0.2 dBFS; prevents 0dBFS clips
    //                         after the +10 dB volume boost.
    //   4. aformat=flt      — interleaved float32 for the soxr aresample step.
    //   5. aresample=soxr   — VHQ precision=33 linear-phase FIR decimation
    //                         from native DSD byte-rate → 88 200 Hz.
    // Output: interleaved float32 at 88 200 Hz, ready for AudioTrack.
    AVFilterGraph   *filter_graph    = nullptr;
    AVFilterContext *buffersrc_ctx   = nullptr;
    AVFilterContext *buffersink_ctx  = nullptr;
    // Reusable workspace frame for pulling filtered output from the sink.
    // Allocated once in dsd_prep_build(); survives across the session lifetime.
    AVFrame         *filt_frame      = nullptr;
};

// ─────────────────────────────────────────────────────────────────────────────
// Helpers
// ─────────────────────────────────────────────────────────────────────────────

static void throw_decoder_exception(JNIEnv *env, int av_err, const char *context) {
    char buf[AV_ERROR_MAX_STRING_SIZE] = {0};
    av_strerror(av_err, buf, sizeof(buf));
    jclass ex_class = env->FindClass(
        "com/androidexpert35/audiophilemusicplayer/data/playback/native_/FFmpegDecoderException");
    if (ex_class != nullptr) {
        std::string msg = std::string(context) + ": " + buf;
        env->ThrowNew(ex_class, msg.c_str());
        env->DeleteLocalRef(ex_class);
    }
}

static void free_session(Session *s) {
    if (s == nullptr) return;
    if (s->swr_ctx != nullptr) {
        swr_free(&s->swr_ctx);
    }
    // Level-3 DSD-prep lavfi resources — freed before the codec/format contexts
    // they reference so there are no dangling pointers inside the graph.
    // avfilter_graph_free also owns and releases buffersrc_ctx / buffersink_ctx.
    if (s->filt_frame != nullptr) {
        av_frame_free(&s->filt_frame);
    }
    if (s->filter_graph != nullptr) {
        avfilter_graph_free(&s->filter_graph);
    }
    if (s->frame != nullptr) {
        av_frame_free(&s->frame);
    }
    if (s->packet != nullptr) {
        av_packet_free(&s->packet);
    }
    if (s->codec_ctx != nullptr) {
        avcodec_free_context(&s->codec_ctx);
    }
    if (s->fmt_ctx != nullptr) {
        avformat_close_input(&s->fmt_ctx);
    }
    if (s->spill_buf != nullptr) {
        av_free(s->spill_buf);
    }
    delete s;
}

// CPU affinity and scheduling policy for the decode thread lives in
// cpu_affinity_policy.{h,cpp} (host-testable classification + OS binding).

// Map decoder's native sample format → chosen packed target + Android encoding.
//
// Output bit-width mirrors the SOURCE file depth rather than blindly upcasting
// everything. The decision tree:
//
//   src_bits <= 16 (or 0 — lossy codecs like MP3/AAC that decode to FLTP but
//                   carry only 16-bit quality)
//       → AV_SAMPLE_FMT_S16 / ENCODING_PCM_16BIT
//
//   17 – 24 bits (e.g. 24-bit FLAC / ALAC)
//       → AV_SAMPLE_FMT_S32 / ENCODING_PCM_32BIT
//         (ENCODING_PCM_24BIT_PACKED is advisory on most HALs and produces
//          inconsistent alignment in the vendor audio path; S32 is the only
//          reliable carrier for 24-bit content.)
//
//   25 – 32 bits integer (e.g. 32-bit WAV int)
//       → AV_SAMPLE_FMT_S32 / ENCODING_PCM_32BIT
//
//   32-bit float / double (native float pipeline or DSD-converted material)
//       → AV_SAMPLE_FMT_FLT / ENCODING_PCM_FLOAT
//
// Returns false only for completely unknown packed formats (safety guard).
static bool choose_output_format(const AVCodecContext *codec_ctx,
                                 AVSampleFormat *out_fmt,
                                 int *out_android_encoding) {
    AVSampleFormat native = codec_ctx->sample_fmt;
    AVSampleFormat packed = av_get_packed_sample_fmt(native);

    // Prefer the codec's own advertised bit depth (set for lossless decoders:
    // FLAC, ALAC, PCM). For lossy decoders (MP3, AAC, Vorbis) this is typically
    // 0 — we derive a conservative depth from the sample format instead.
    int src_bits = codec_ctx->bits_per_raw_sample;

    if (src_bits <= 0) {
        switch (packed) {
            case AV_SAMPLE_FMT_U8:
                src_bits = 8;
                break;
            case AV_SAMPLE_FMT_S16:
                src_bits = 16;
                break;
            case AV_SAMPLE_FMT_S32:
                // S32P is used by some lossless decoders for 24-bit sources even
                // when bits_per_raw_sample is absent — treat conservatively as 24.
                src_bits = 24;
                break;
            case AV_SAMPLE_FMT_FLT:
            case AV_SAMPLE_FMT_DBL:
                // Lossy decoders (MP3, AAC, Vorbis) always decode to FLTP.
                // Their quality ceiling is 16-bit; output S16 to save CPU/battery.
                src_bits = 16;
                break;
            default:
                return false;
        }
    }

    if (src_bits <= 16) {
        // Standard CD-quality and all lossy compressed formats.
        *out_fmt             = AV_SAMPLE_FMT_S16;
        *out_android_encoding = ENCODING_PCM_16BIT;
    } else if (src_bits <= 24) {
        // High-resolution lossless (24-bit FLAC, ALAC, DSD-PCM).
        // Carried as zero-padded S32 — the only alignment-safe Android path.
        *out_fmt             = AV_SAMPLE_FMT_S32;
        *out_android_encoding = ENCODING_PCM_32BIT;
    } else {
        // 32-bit sources: keep integer as S32, float as FLT.
        if (packed == AV_SAMPLE_FMT_FLT || packed == AV_SAMPLE_FMT_DBL) {
            *out_fmt             = AV_SAMPLE_FMT_FLT;
            *out_android_encoding = ENCODING_PCM_FLOAT;
        } else {
            *out_fmt             = AV_SAMPLE_FMT_S32;
            *out_android_encoding = ENCODING_PCM_32BIT;
        }
    }
    return true;
}

static int bytes_per_sample_for(AVSampleFormat fmt) {
    return av_get_bytes_per_sample(fmt);
}

static bool is_dsd_codec(AVCodecID codec_id) {
    switch (codec_id) {
        case AV_CODEC_ID_DSD_LSBF:
        case AV_CODEC_ID_DSD_MSBF:
        case AV_CODEC_ID_DSD_LSBF_PLANAR:
        case AV_CODEC_ID_DSD_MSBF_PLANAR:
            return true;
        default:
            return false;
    }
}

// When DSD is force-decoded to PCM (no direct-USB DAC available), FFmpeg's DSD
// decoders emit float PCM at `dsd_byte_rate` — i.e. 352 800 Hz for DSD64,
// 705 600 Hz for DSD128, 1 411 200 Hz for DSD256. Android's AudioTrack caps
// out around 192 kHz on most SoCs and rejects anything higher with:
//     getMinBufferSize(): 352800 Hz is not a supported sample rate.
//
// 88 200 Hz = 44 100 × 2 is chosen as the Tier-3 PCM carrier rate because:
//   1. Integer decimation ratio from all DSD families:
//        DSD64  (2 822 400 Hz) ÷ 32 = 88 200 Hz  (exact)
//        DSD128 (5 644 800 Hz) ÷ 64 = 88 200 Hz  (exact)
//        DSD256 (11 289 600 Hz) ÷ 128 = 88 200 Hz (exact)
//      Contrast with 192 kHz, which is NOT a sub-multiple of 44 100 Hz and
//      forces the resampler to use a rational approximation internally.
//   2. Universally supported by every Android AudioTrack path — speaker HAL,
//      USB class-compliant fallback, and A2DP LDAC all accept 88.2 kHz.
//   3. DSD64's useful audio bandwidth is ≤ 50 kHz; 88.2 kHz / Nyquist = 44.1 kHz
//      preserves the entire audible spectrum without wasting headroom.
//
// Must stay in sync with DSD_PCM_FALLBACK_RATE_HZ in BitPerfectPlaybackEngine.kt.
static int pick_forced_pcm_target_rate(int /*src_rate*/) {
    return 88200;
}

static int derive_dsd_rate_multiplier(int sample_rate_hz) {
    if (sample_rate_hz <= 0 || sample_rate_hz % 44100 != 0) return 0;
    const int multiplier = sample_rate_hz / 44100;
    switch (multiplier) {
        case 64:
        case 128:
        case 256:
            return multiplier;
        default:
            return 0;
    }
}

static std::string normalize_container_name(const AVInputFormat *iformat) {
    if (iformat == nullptr || iformat->name == nullptr) return "";
    const std::string name(iformat->name);
    if (name.find("dsf") != std::string::npos) return "DSF";
    if (name.find("dsdiff") != std::string::npos || name.find("dff") != std::string::npos) return "DSDIFF";
    if (name.find("wavpack") != std::string::npos) return "WavPack DSD";
    return name;
}

static uint8_t reverse_bits_u8(uint8_t value) {
    value = static_cast<uint8_t>(((value & 0xF0U) >> 4U) | ((value & 0x0FU) << 4U));
    value = static_cast<uint8_t>(((value & 0xCCU) >> 2U) | ((value & 0x33U) << 2U));
    value = static_cast<uint8_t>(((value & 0xAAU) >> 1U) | ((value & 0x55U) << 1U));
    return value;
}

static bool spill_reserve(Session *s, int additional_len) {
    if (additional_len <= 0) return true;
    int required = s->spill_len + additional_len;
    if (required <= s->spill_cap) return true;
    int new_cap = s->spill_cap == 0 ? 16384 : s->spill_cap * 2;
    while (new_cap < required) new_cap *= 2;
    auto *grown = static_cast<uint8_t *>(av_realloc(s->spill_buf, new_cap));
    if (grown == nullptr) return false;
    s->spill_buf = grown;
    s->spill_cap = new_cap;
    return true;
}

// Decide whether the decoder's native output is already shaped correctly for
// our chosen packed target. If so, we skip swresample on the hot path.
static bool needs_swr(const AVCodecContext *codec_ctx, AVSampleFormat target) {
    AVSampleFormat native = codec_ctx->sample_fmt;
    if (av_sample_fmt_is_planar(native)) return true;
    if (native != target) return true;
    // Swresample is also required if the channel layout changes; we keep the
    // source layout so this is effectively a no-op for 1/2 channel content.
    return false;
}

// Append `len` bytes to the spill buffer, growing as needed.
static bool spill_append(Session *s, const uint8_t *data, int len) {
    if (len <= 0) return true;
    if (!spill_reserve(s, len)) return false;
    std::memcpy(s->spill_buf + s->spill_len, data, len);
    s->spill_len += len;
    return true;
}

// Drain spill into the caller's ByteArray segment. Shifts remaining bytes
// down to the head of the spill buffer.
static int spill_drain(Session *s, uint8_t *dst, int dst_cap) {
    if (s->spill_len <= 0) return 0;
    int n = std::min(dst_cap, s->spill_len);
    std::memcpy(dst, s->spill_buf, n);
    int remaining = s->spill_len - n;
    if (remaining > 0) {
        std::memmove(s->spill_buf, s->spill_buf + n, remaining);
    }
    s->spill_len = remaining;
    return n;
}

// ─────────────────────────────────────────────────────────────────────────────
// Level-3 DSD-prep lavfi filter graph
//
// Applied on ALL DSD → PCM fallback sessions (is_resampled_dsd == true).
// The graph runs in the hot decode loop and processes the raw high-rate FLTP
// stream before the resulting buffer enters the spill queue.
//
// Pipeline (soxr VHQ folded into lavfi — simple, no manual soxr C-API calls):
//   1. volume=10dB          — SACD Scarlet Book headroom restore
//   2. lowpass=28kHz        — Butterworth biquad strips ultrasonic DSD noise
//                             BEFORE decimation so it cannot alias downward.
//   3. alimiter=−0.2dBFS    — Brickwall limiter prevents 0dBFS clips caused
//                             by the +10 dB boost on hot-mastered SACDs.
//   4. aformat=flt          — interleaved float32; required input for soxr.
//   5. aresample=osr=88200  — soxr VHQ (precision=33, linear-phase) decimates
//     :resampler=soxr          FLT@native_rate → FLT@88 200 Hz entirely inside
//     :precision=33             FFmpeg's lavfi resampler; no external soxr calls.
//
// Spill/JNI: receives FLT at 88 200 Hz directly from the abuffersink.
//
// Fall-back (lavfi build failed → filter_graph == nullptr):
//   swr_ctx (libswresample sinc) handles format conversion and rate decimation.
//   No gain restore or ultrasonic noise filter is applied in the sinc path.
// ─────────────────────────────────────────────────────────────────────────────

// Exact lavfi filter description for the Level-3 DSD-fallback path.
//
//   volume=10dB                — restores the −6 dB SACD Scarlet Book headroom
//                                (+4 dB beyond the base restore).
//   lowpass=28kHz (Q=0.707)    — Butterworth biquad strips extreme 1-bit DSD
//                                quantisation noise BEFORE soxr decimation so
//                                it cannot alias into the audible band.
//   alimiter=limit=0.977       — Lookahead brickwall at ≈ −0.2 dBFS; catches
//                                peaks above 0 dBFS after the +10 dB boost.
//   aformat=sample_fmts=flt    — interleaved float32; soxr requires packed FLT.
//   aresample=osr=88200        — soxr VHQ (precision=33, linear-phase FIR)
//     :resampler=soxr:           decimates FLT@native_DSD_byte_rate → FLT@88 200 Hz.
//     :precision=33             88 200 Hz = DSD64÷32 = DSD128÷64 = DSD256÷128
//                                (exact integer ratios — no rational approximation).
static const char *const DSD_PREP_FILTER_STR =
    "volume=10dB"
    ",lowpass=f=28000:width_type=q:width=0.707"
    ",alimiter=limit=0.977"
    ",aformat=sample_fmts=flt"
    ",aresample=osr=88200:resampler=soxr:precision=33";

/**
 * Constructs (or reconstructs) the DSD-prep lavfi filter graph for the given
 * session.  Safe to call repeatedly — always frees a previously built graph
 * before allocating a new one.
 *
 * Input parameters are derived from `s->codec_ctx` at the time of the call, so
 * the codec context must already be open and configured before invoking this.
 *
 * @param s  Session that will own the new filter graph.
 * @return   true on success; false if any lavfi API call fails, in which case
 *           s->filter_graph is released and set to nullptr so callers can detect
 *           the absence of the graph and fall back gracefully.
 */
static bool dsd_prep_build(Session *s) {
    // Idempotent: release any existing graph so the function can be used to
    // rebuild state (e.g. after a seek resets the codec flush state).
    if (s->filter_graph != nullptr) {
        // avfilter_graph_free also releases buffersrc_ctx and buffersink_ctx.
        avfilter_graph_free(&s->filter_graph);
        s->buffersrc_ctx  = nullptr;
        s->buffersink_ctx = nullptr;
    }

    const AVFilter *abuffersrc  = avfilter_get_by_name("abuffer");
    const AVFilter *abuffersink = avfilter_get_by_name("abuffersink");
    if (abuffersrc == nullptr || abuffersink == nullptr) {
        ALOGE("dsd_prep_build: lavfi 'abuffer'/'abuffersink' not in codec registry — "
              "was libavfilter built with --enable-avfilter?");
        return false;
    }

    s->filter_graph = avfilter_graph_alloc();
    if (s->filter_graph == nullptr) {
        ALOGE("dsd_prep_build: avfilter_graph_alloc failed (out of memory)");
        return false;
    }

    // Describe the input pad: DSD decoders emit planar float (FLTP) at the
    // DSD byte-rate.  The channel layout is forwarded unchanged from the codec.
    char ch_desc[64] = "stereo";
    av_channel_layout_describe(&s->codec_ctx->ch_layout, ch_desc, sizeof(ch_desc));

    char abuf_args[256];
    snprintf(abuf_args, sizeof(abuf_args),
             "time_base=1/%d:sample_rate=%d:sample_fmt=%s:channel_layout=%s",
             s->codec_ctx->sample_rate,
             s->codec_ctx->sample_rate,
             av_get_sample_fmt_name(s->codec_ctx->sample_fmt),
             ch_desc);

    int ret = avfilter_graph_create_filter(
        &s->buffersrc_ctx, abuffersrc, "in", abuf_args, nullptr, s->filter_graph);
    if (ret < 0) {
        char errbuf[AV_ERROR_MAX_STRING_SIZE];
        av_strerror(ret, errbuf, sizeof(errbuf));
        ALOGE("dsd_prep_build: avfilter_graph_create_filter(abuffer) failed: %s", errbuf);
        avfilter_graph_free(&s->filter_graph);
        return false;
    }

    ret = avfilter_graph_create_filter(
        &s->buffersink_ctx, abuffersink, "out", nullptr, nullptr, s->filter_graph);
    if (ret < 0) {
        char errbuf[AV_ERROR_MAX_STRING_SIZE];
        av_strerror(ret, errbuf, sizeof(errbuf));
        ALOGE("dsd_prep_build: avfilter_graph_create_filter(abuffersink) failed: %s", errbuf);
        avfilter_graph_free(&s->filter_graph);
        return false;
    }

    // Wire the in/out endpoint stubs together through the filter description
    // string.  avfilter_graph_parse_ptr takes ownership of both inout lists.
    AVFilterInOut *outputs = avfilter_inout_alloc();
    AVFilterInOut *inputs  = avfilter_inout_alloc();
    if (outputs == nullptr || inputs == nullptr) {
        avfilter_inout_free(&outputs);
        avfilter_inout_free(&inputs);
        avfilter_graph_free(&s->filter_graph);
        return false;
    }

    // "outputs" links the "in" pad to the first filter in the chain.
    outputs->name       = av_strdup("in");
    outputs->filter_ctx = s->buffersrc_ctx;
    outputs->pad_idx    = 0;
    outputs->next       = nullptr;

    // "inputs" links the last filter in the chain to the "out" pad.
    inputs->name        = av_strdup("out");
    inputs->filter_ctx  = s->buffersink_ctx;
    inputs->pad_idx     = 0;
    inputs->next        = nullptr;

    ret = avfilter_graph_parse_ptr(
        s->filter_graph, DSD_PREP_FILTER_STR, &inputs, &outputs, nullptr);
    // avfilter_graph_parse_ptr owns inputs/outputs and frees them on return.
    avfilter_inout_free(&inputs);
    avfilter_inout_free(&outputs);
    if (ret < 0) {
        char errbuf[AV_ERROR_MAX_STRING_SIZE];
        av_strerror(ret, errbuf, sizeof(errbuf));
        ALOGE("dsd_prep_build: avfilter_graph_parse_ptr failed: %s  (filter=\"%s\")",
              errbuf, DSD_PREP_FILTER_STR);
        avfilter_graph_free(&s->filter_graph);
        return false;
    }

    ret = avfilter_graph_config(s->filter_graph, nullptr);
    if (ret < 0) {
        char errbuf[AV_ERROR_MAX_STRING_SIZE];
        av_strerror(ret, errbuf, sizeof(errbuf));
        ALOGE("dsd_prep_build: avfilter_graph_config failed: %s", errbuf);
        avfilter_graph_free(&s->filter_graph);
        return false;
    }

    // filt_frame is reused across every sink-pull iteration; allocate once and
    // keep it for the session lifetime so we avoid malloc churn on the hot path.
    if (s->filt_frame == nullptr) {
        s->filt_frame = av_frame_alloc();
        if (s->filt_frame == nullptr) {
            ALOGE("dsd_prep_build: av_frame_alloc for filt_frame failed (out of memory)");
            avfilter_graph_free(&s->filter_graph);
            return false;
        }
    }

    ALOGD("DSD-prep lavfi graph ready: abuffer(%dHz %s %s) → [%s] → abuffersink@88200Hz",
          s->codec_ctx->sample_rate,
          av_get_sample_fmt_name(s->codec_ctx->sample_fmt),
          ch_desc,
          DSD_PREP_FILTER_STR);
    return true;
}

/**
 * Pushes one decoded AVFrame through the DSD-prep lavfi graph and appends the
 * resulting PCM data to the session spill buffer at 88 200 Hz.
 *
 * The soxr VHQ (precision=33, linear-phase) decimation is performed entirely
 * inside lavfi via the `aresample=osr=88200:resampler=soxr:precision=33` stage
 * at the end of DSD_PREP_FILTER_STR.  This function simply pulls frames from
 * the abuffersink — which already delivers FLT@88 200 Hz — and appends them
 * to the spill buffer.  No manual soxr C-API calls are needed here.
 *
 * @param s     Session owning the filter graph and spill buffer.
 * @param frame Decoded FLTP frame at the native DSD byte-rate.  The graph makes
 *              its own reference (AV_BUFFERSRC_FLAG_KEEP_REF) so the caller's
 *              av_frame_unref() remains valid after this call.
 * @return      true on success; false on any lavfi API failure.
 */
static bool frame_to_spill_via_lavfi(Session *s, AVFrame *frame) {
    // Push the decoded frame into the source buffer.  KEEP_REF so the caller's
    // av_frame_unref(s->frame) in nativeReadNextBuffer remains valid.
    const int push_ret = av_buffersrc_add_frame_flags(
        s->buffersrc_ctx, frame, AV_BUFFERSRC_FLAG_KEEP_REF);
    if (push_ret < 0) {
        char errbuf[AV_ERROR_MAX_STRING_SIZE];
        av_strerror(push_ret, errbuf, sizeof(errbuf));
        ALOGE("frame_to_spill_via_lavfi: av_buffersrc_add_frame_flags failed: %s", errbuf);
        return false;
    }

    // Drain all frames the graph produces for this input.
    // The abuffersink delivers FLT@88 200 Hz — soxr VHQ runs inside lavfi.
    // The first few calls may yield 0 frames while the soxr FIR kernel primes;
    // this is normal and the caller handles empty spill gracefully.
    while (true) {
        const int pull_ret = av_buffersink_get_frame(s->buffersink_ctx, s->filt_frame);
        if (pull_ret == AVERROR(EAGAIN) || pull_ret == AVERROR_EOF) {
            break;   // no output yet — normal during soxr FIR priming or after flush
        }
        if (pull_ret < 0) {
            av_frame_unref(s->filt_frame);
            return false;
        }

        // Output is FLT (interleaved float32) at 88 200 Hz from the lavfi soxr stage.
        const int byte_count =
            s->filt_frame->nb_samples * s->out_bytes_per_sample * s->out_channels;
        if (!spill_append(s, s->filt_frame->data[0], byte_count)) {
            av_frame_unref(s->filt_frame);
            return false;
        }
        av_frame_unref(s->filt_frame);
    }
    return true;
}

// ─────────────────────────────────────────────────────────────────────────────
// Hi-Res Remaster metadata reader — TRACK_PEAK → pre-expansion headroom gain
//
// Reads REPLAYGAIN_TRACK_PEAK from the file's AVDictionary at open time and
// converts the linear peak value into the minimum attenuation (in dB) needed
// to give the Hi-Res Remaster *upward expansion* stage room to lift peaks by
// up to ~2.5 dB without clipping.
//
// Why TRACK_PEAK, not TRACK_GAIN:
//   TRACK_GAIN normalises perceived loudness across tracks — applying it
//   lowers the output level by 8–12 dB on modern, hot-mastered CD releases.
//   TRACK_PEAK encodes only the true-peak ceiling already in the file.
//   Using it lets us derive the exact headroom available and apply the
//   minimum attenuation required — the user perceives no volume drop.
//
// Formula:
//   headroom_db  = −20 × log₁₀(track_peak)        (headroom already in the file)
//   gain_db      = clamp(headroom_db − 3.0, −6.0, 0.0)
//     i.e. attenuate exactly by the headroom *missing* relative to the 3 dB
//     the expansion stage needs; never more than 6 dB, never a boost.
//     (The previous formula, max(−2, −headroom), was inverted: it attenuated
//     quiet masters the most and hot masters — the ones needing protection —
//     the least.)
//
// Example table:
//   peak=1.00 → headroom=0.0 dB  → gain=−3.0 dB  (full protection)
//   peak=0.98 → headroom=0.2 dB  → gain=−2.8 dB
//   peak=0.85 → headroom=1.4 dB  → gain=−1.6 dB
//   peak=0.71 → headroom=3.0 dB  → gain= 0.0 dB  (already enough room)
//   peak=1.20 → headroom=−1.6 dB → gain=−4.6 dB  (true-peak clipping source)
//   missing   →                   → gain=−3.0 dB  (assume peak = 1.0)
//
// The value is exposed to the Kotlin layer via nativeGetReplayGainDb so it
// can be forwarded to SueBridge.nativeCreate for the Hi-Res Remaster chain.
// All DSP (compand, alimiter, soxr upsampling) lives in sue_bridge.
// ─────────────────────────────────────────────────────────────────────────────

/// Safe conservative gain applied when no TRACK_PEAK tag is present.
/// −3.0 dB assumes the worst case (peak = 1.0) and creates exactly the room
/// the expansion stage can add back — peaks land at −0.5 dBFS afterwards.
static constexpr float REMASTER_DEFAULT_GAIN_DB = -3.0f;

/// Headroom (dB) the Hi-Res Remaster expansion stage needs above the file's
/// true peak.  Matches the compand curve in sue_bridge's
/// build_hires_remaster_chain (max boost +2.5 dB, output ceiling −0.5 dBFS).
static constexpr float REMASTER_EXPANSION_HEADROOM_DB = 3.0f;

/// Never attenuate by more than this, even for files whose TRACK_PEAK reports
/// inter-sample clipping (peak > 1.0).
static constexpr float REMASTER_MAX_ATTENUATION_DB = -6.0f;

/**
 * Reads REPLAYGAIN_TRACK_PEAK from the file's AVDictionary metadata and
 * converts it to the minimum gain-in-dB needed to give the Hi-Res Remaster
 * upward-expansion stage clipping-free headroom without any audible volume
 * reduction.
 *
 * The linear peak value is converted to headroom via:
 *   headroom_db = −20 × log₁₀(track_peak)
 * and the applied gain covers only the missing portion of the 3 dB the
 * expansion stage needs, clamped to [−6, 0]:
 *   gain_db = clamp(headroom_db − 3.0, −6.0, 0.0)
 *
 * Search order:
 *   1. Stream-level metadata (per-track accuracy, preferred).
 *   2. Format-level (container) metadata.
 * Tag names are matched case-insensitively (av_dict_get flags=0).
 *
 * @param fmt_ctx      Open format context with populated metadata dictionaries.
 * @param stream_index Index of the audio stream to inspect first.
 * @return Peak-derived headroom gain in dB (≤ 0), or REMASTER_DEFAULT_GAIN_DB
 *         when no valid REPLAYGAIN_TRACK_PEAK tag exists.
 */
static float extract_replaygain_db(const AVFormatContext *fmt_ctx, int stream_index) {
    if (fmt_ctx == nullptr) return REMASTER_DEFAULT_GAIN_DB;

    // Only TRACK_PEAK is consulted — TRACK_GAIN normalises loudness and would
    // produce an 8–12 dB volume drop on typical hot-mastered CD sources.
    static const char *const kTags[] = {
        "REPLAYGAIN_TRACK_PEAK",
    };

    AVDictionary *const dicts[] = {
        (stream_index >= 0 && stream_index < static_cast<int>(fmt_ctx->nb_streams))
            ? fmt_ctx->streams[stream_index]->metadata
            : nullptr,
        fmt_ctx->metadata,
    };

    for (const char *tag : kTags) {
        for (AVDictionary *dict : dicts) {
            if (dict == nullptr) continue;
            const AVDictionaryEntry *entry = av_dict_get(dict, tag, nullptr, 0);
            if (entry == nullptr || entry->value == nullptr || entry->value[0] == '\0') continue;

            char *end_ptr = nullptr;
            const float peak = std::strtof(entry->value, &end_ptr);

            // Reject unparseable, zero, or negative peak values.  A peak > 1.0
            // is technically possible (already clipping); the clamp below caps
            // the extra attenuation at REMASTER_MAX_ATTENUATION_DB (−6.0 dB).
            if (end_ptr == entry->value || peak <= 0.0f) {
                ALOGD("ReplayGain: tag '%s' = \"%s\" — invalid, using default %.1f dB",
                      tag, entry->value, REMASTER_DEFAULT_GAIN_DB);
                return REMASTER_DEFAULT_GAIN_DB;
            }

            // Convert linear peak to available headroom, then attenuate by the
            // headroom *missing* relative to what the expansion stage needs.
            // Files that already have ≥3 dB of headroom pass through with 0 dB;
            // hot masters (peak ≈ 1.0) get the full −3 dB of protection.
            const float headroom_db = -20.0f * std::log10f(peak);
            const float gain_db     = std::fmaxf(
                REMASTER_MAX_ATTENUATION_DB,
                std::fminf(0.0f, headroom_db - REMASTER_EXPANSION_HEADROOM_DB));

            ALOGD("ReplayGain: tag '%s' = \"%s\" → peak=%.4f headroom=%.2f dB → gain=%.2f dB",
                  tag, entry->value, peak, headroom_db, gain_db);
            return gain_db;
        }
    }

    ALOGD("ReplayGain: REPLAYGAIN_TRACK_PEAK not found — using default %.1f dB",
          REMASTER_DEFAULT_GAIN_DB);
    return REMASTER_DEFAULT_GAIN_DB;
}

// Push one decoded AVFrame into the spill buffer as packed interleaved PCM
// in the session's chosen output format.
static bool frame_to_spill(Session *s, AVFrame *frame) {
    int src_samples = frame->nb_samples;
    if (src_samples <= 0) return true;

    // Level-3 DSD fallback path: route through the DSD-prep lavfi graph to
    // apply gain normalisation (+10 dB) and ultrasonic noise suppression
    // (28 kHz Butterworth LPF) before the float buffer reaches libsoxr.
    // Levels 1 (native DSD) and 2 (DoP) are served by nativeReadNextDsdBuffer
    // and never reach this function.
    if (s->filter_graph != nullptr) {
        return frame_to_spill_via_lavfi(s, frame);
    }

    int bytes_per_frame = s->out_bytes_per_sample * s->out_channels;

    if (s->swr_ctx != nullptr) {
        // Upper bound for output sample count accounting for any internal delay.
        int max_out_samples = swr_get_out_samples(s->swr_ctx, src_samples);
        if (max_out_samples < src_samples) max_out_samples = src_samples;

        int needed_bytes = max_out_samples * bytes_per_frame;
        // Ensure spill has capacity for the worst case, then write directly into it.
        if (!spill_reserve(s, needed_bytes)) return false;
        uint8_t *dst = s->spill_buf + s->spill_len;
        uint8_t *dst_planes[1] = { dst };
        int converted = swr_convert(
            s->swr_ctx,
            dst_planes,
            max_out_samples,
            const_cast<const uint8_t **>(frame->extended_data),
            src_samples
        );
        if (converted < 0) return false;
        s->spill_len += converted * bytes_per_frame;
    } else {
        // Pass-through path — already packed, already the target format.
        int byte_count = src_samples * bytes_per_frame;
        if (!spill_append(s, frame->extended_data[0], byte_count)) return false;
    }
    return true;
}

static bool append_normalized_dsd_packet_to_spill(Session *s, const AVPacket *packet) {
    if (s == nullptr || packet == nullptr || packet->data == nullptr || packet->size <= 0) {
        return true;
    }

    const bool reverse_bits =
        s->codec_ctx->codec_id == AV_CODEC_ID_DSD_LSBF ||
        s->codec_ctx->codec_id == AV_CODEC_ID_DSD_LSBF_PLANAR;
    const bool planar =
        s->codec_ctx->codec_id == AV_CODEC_ID_DSD_LSBF_PLANAR ||
        s->codec_ctx->codec_id == AV_CODEC_ID_DSD_MSBF_PLANAR;

    if (!spill_reserve(s, packet->size)) return false;

    // Bit-order normalization happens exactly once here. Every DSD byte that
    // leaves JNI is MSB-first, regardless of whether the source container stored
    // it LSBF (DSF) or MSBF (DSDIFF). Both DoP and native DSD sinks consume this
    // canonical MSB-first representation.
    if (!planar) {
        for (int i = 0; i < packet->size; ++i) {
            const auto value = static_cast<uint8_t>(packet->data[i]);
            s->spill_buf[s->spill_len + i] = reverse_bits ? reverse_bits_u8(value) : value;
        }
        s->spill_len += packet->size;
        return true;
    }

    const int channel_count = std::max(1, s->out_channels);
    if (packet->size % channel_count != 0) {
        return false;
    }
    const int plane_size = packet->size / channel_count;
    int write_offset = s->spill_len;
    for (int sample_index = 0; sample_index < plane_size; ++sample_index) {
        for (int channel_index = 0; channel_index < channel_count; ++channel_index) {
            const auto value = static_cast<uint8_t>(
                packet->data[channel_index * plane_size + sample_index]);
            s->spill_buf[write_offset++] = reverse_bits ? reverse_bits_u8(value) : value;
        }
    }
    s->spill_len = write_offset;
    return true;
}

/**
 * Trims normalized raw-DSD bytes preceding an exact seek target.
 *
 * FFmpeg demuxers seek DSF/DSDIFF streams to packet boundaries. A backward
 * seek therefore positions the decoder slightly before the requested time.
 * Raw DSD bypasses avcodec frame timestamps, so the generic decoder cannot
 * discard that packet prefix for us. Convert the target delta into interleaved
 * channel bytes and remove it from the normalized spill buffer here.
 *
 * When a complete packet still ends before the target, its appended bytes are
 * discarded and the pending target remains armed for the next packet.
 */
static void trim_dsd_spill_to_exact_seek(
        Session        *s,
        const AVPacket *packet,
        int             spill_start) {
    if (s == nullptr || packet == nullptr ||
        s->exact_dsd_seek_target_us == AV_NOPTS_VALUE ||
        s->codec_ctx == nullptr || s->fmt_ctx == nullptr) {
        return;
    }

    const int64_t packet_ts = packet->pts != AV_NOPTS_VALUE ? packet->pts : packet->dts;
    if (packet_ts == AV_NOPTS_VALUE) {
        ALOGW("Exact DSD seek: post-seek packet has no timestamp; using demuxer boundary");
        s->exact_dsd_seek_target_us = AV_NOPTS_VALUE;
        return;
    }

    AVStream *stream = s->fmt_ctx->streams[s->audio_stream_index];
    const int64_t packet_start_us = av_rescale_q(
        packet_ts,
        stream->time_base,
        AVRational{1, AV_TIME_BASE});
    const int64_t delta_us = s->exact_dsd_seek_target_us - packet_start_us;
    if (delta_us <= 0) {
        s->exact_dsd_seek_target_us = AV_NOPTS_VALUE;
        return;
    }

    const int channel_count = std::max(1, s->codec_ctx->ch_layout.nb_channels);
    const int64_t byte_rate_all_channels =
        static_cast<int64_t>(s->codec_ctx->sample_rate) * channel_count;
    int64_t bytes_to_skip = av_rescale(
        delta_us,
        byte_rate_all_channels,
        AV_TIME_BASE);
    // Normalized planar DSD is interleaved one byte per channel. Preserve that
    // boundary so the first retained sample never swaps channel alignment.
    bytes_to_skip -= bytes_to_skip % channel_count;

    const int appended_bytes = s->spill_len - spill_start;
    if (bytes_to_skip >= appended_bytes) {
        s->spill_len = spill_start;
        return;
    }

    if (bytes_to_skip > 0) {
        const int retained_bytes = appended_bytes - static_cast<int>(bytes_to_skip);
        std::memmove(
            s->spill_buf + spill_start,
            s->spill_buf + spill_start + bytes_to_skip,
            retained_bytes);
        s->spill_len = spill_start + retained_bytes;
    }

    ALOGD(
        "Exact DSD seek: target=%lldus packetStart=%lldus trimmed=%lld bytes",
        static_cast<long long>(s->exact_dsd_seek_target_us),
        static_cast<long long>(packet_start_us),
        static_cast<long long>(bytes_to_skip));
    s->exact_dsd_seek_target_us = AV_NOPTS_VALUE;
}

// ─────────────────────────────────────────────────────────────────────────────
// Non-JNI internal C++ session API
//
// These C-linkage functions expose the Session decode machinery to the new
// FfmpegAudioDecoder / IAudioDecoder layer without any JNI types.
//
// They share the existing static helpers (frame_to_spill, spill_drain, etc.)
// and the Session struct verbatim — zero code duplication.
//
// Design notes:
//   • `FfmpegSession` is typedef'd to `Session` so external translation units
//     can forward-declare it as `struct FfmpegSession` without seeing the full
//     Session definition (which pulls in all libavcodec headers).
//   • The AudioTrack output path that previously lived in the Kotlin write-loop
//     (Kotlin ByteBuffer → AudioTrack.write()) is entirely absent from these
//     functions.  Callers receive raw bytes and decide what to do with them.
// ─────────────────────────────────────────────────────────────────────────────

// Expose Session under the external name FfmpegSession so ffmpeg_audio_decoder.cpp
// can forward-declare it without including all of ffmpeg_bridge's private headers.
using FfmpegSession = Session;

#include "i_audio_decoder.h"   // DecoderFormat, kDecoderEof, kDecoderRecoverableError

// ── ffmpeg_session_open ────────────────────────────────────────────────────────

/**
 * Open an FFmpeg decode session for the given file path without any JNI
 * environment or exception-throw path.
 *
 * Mirrors nativeOpen() except:
 *   - Returns a typed FfmpegSession* instead of a jlong.
 *   - Failure is reported via error_out string, not by throwing across JNI.
 *   - No DirectByteBuffer or AudioTrack interaction is performed.
 *   - CPU affinity is applied to the calling thread (same as nativeOpen).
 *
 * @param path       Null-terminated filesystem path.
 * @param force_pcm  Activate the Tier-3 DSD → PCM lavfi+soxr VHQ path.
 * @param error_out  Optional output string for failure description.
 * @return           Non-null FfmpegSession* on success; nullptr on failure.
 */
extern "C"
FfmpegSession *ffmpeg_session_open(
        const char  *path,
        bool         force_pcm,
        std::string *error_out) noexcept
{
    if (path == nullptr || path[0] == '\0') {
        if (error_out) *error_out = "path must not be null or empty";
        return nullptr;
    }

    configure_current_thread_priority();

    auto *s = new (std::nothrow) Session();
    if (s == nullptr) {
        if (error_out) *error_out = "out of memory allocating Session";
        return nullptr;
    }

    auto report = [&](int av_err, const char *ctx_msg) -> FfmpegSession * {
        char buf[AV_ERROR_MAX_STRING_SIZE] = {0};
        av_strerror(av_err, buf, sizeof(buf));
        if (error_out) *error_out = std::string(ctx_msg) + ": " + buf;
        free_session(s);
        return nullptr;
    };

    int ret = avformat_open_input(&s->fmt_ctx, path, nullptr, nullptr);
    if (ret < 0) return report(ret, "avformat_open_input");

    ret = avformat_find_stream_info(s->fmt_ctx, nullptr);
    if (ret < 0) return report(ret, "avformat_find_stream_info");

    ret = av_find_best_stream(s->fmt_ctx, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
    if (ret < 0) return report(ret, "av_find_best_stream");
    s->audio_stream_index = ret;

    AVStream *stream = s->fmt_ctx->streams[s->audio_stream_index];
    const AVCodec *codec = avcodec_find_decoder(stream->codecpar->codec_id);
    if (codec == nullptr) return report(AVERROR_DECODER_NOT_FOUND, "avcodec_find_decoder");

    s->codec_ctx = avcodec_alloc_context3(codec);
    if (s->codec_ctx == nullptr) return report(AVERROR(ENOMEM), "avcodec_alloc_context3");

    ret = avcodec_parameters_to_context(s->codec_ctx, stream->codecpar);
    if (ret < 0) return report(ret, "avcodec_parameters_to_context");

    ret = avcodec_open2(s->codec_ctx, codec, nullptr);
    if (ret < 0) return report(ret, "avcodec_open2");

    s->is_dsd = is_dsd_codec(s->codec_ctx->codec_id);
    const bool forced_pcm_from_dsd = s->is_dsd && force_pcm;
    if (forced_pcm_from_dsd) s->is_dsd = false;

    s->out_sample_rate = s->codec_ctx->sample_rate;
    s->out_channels    = s->codec_ctx->ch_layout.nb_channels;

    if (s->is_dsd) {
        s->out_sample_fmt       = AV_SAMPLE_FMT_NONE;
        s->out_android_encoding = 0;
        s->out_bytes_per_sample = 1;
        s->source_bit_depth     = 1;
        s->dsd_rate_multiplier  = derive_dsd_rate_multiplier(s->out_sample_rate);
        s->container_name       = normalize_container_name(s->fmt_ctx->iformat);
    } else {
        AVSampleFormat target_fmt    = AV_SAMPLE_FMT_NONE;
        int            android_enc   = 0;
        if (!choose_output_format(s->codec_ctx, &target_fmt, &android_enc)) {
            if (error_out) *error_out = "decoder sample format has no Android PCM carrier";
            free_session(s);
            return nullptr;
        }
        s->out_sample_fmt       = target_fmt;
        s->out_android_encoding = android_enc;
        s->out_bytes_per_sample = bytes_per_sample_for(target_fmt);

        if (forced_pcm_from_dsd) {
            s->out_sample_fmt        = AV_SAMPLE_FMT_FLT;
            s->out_android_encoding  = ENCODING_PCM_FLOAT;
            s->out_bytes_per_sample  = bytes_per_sample_for(AV_SAMPLE_FMT_FLT);
            s->source_bit_depth      = 32;
            s->out_sample_rate       = pick_forced_pcm_target_rate(s->codec_ctx->sample_rate);
            s->dsd_rate_multiplier   = derive_dsd_rate_multiplier(s->codec_ctx->sample_rate * 8);
            s->container_name        = normalize_container_name(s->fmt_ctx->iformat);
            s->is_resampled_dsd      = true;
            if (!dsd_prep_build(s)) {
                ALOGW("ffmpeg_session_open: DSD-prep lavfi+soxr graph build failed — "
                      "falling back to libswresample sinc");
            }
        }
    }

    if (s->out_channels <= 0 || s->out_sample_rate <= 0) {
        if (error_out) *error_out = "decoder returned invalid channel/sample-rate";
        free_session(s);
        return nullptr;
    }

    const bool needs_rate_conv = !s->is_dsd && s->out_sample_rate != s->codec_ctx->sample_rate;
    if (!s->is_dsd && s->filter_graph == nullptr &&
        (needs_swr(s->codec_ctx, s->out_sample_fmt) || needs_rate_conv)) {
        s->swr_ctx = swr_alloc();
        if (s->swr_ctx == nullptr) {
            if (error_out) *error_out = "swr_alloc failed (OOM)";
            free_session(s);
            return nullptr;
        }
        AVChannelLayout in_layout{}, out_layout{};
        av_channel_layout_copy(&in_layout,  &s->codec_ctx->ch_layout);
        av_channel_layout_copy(&out_layout, &s->codec_ctx->ch_layout);
        ret = swr_alloc_set_opts2(
            &s->swr_ctx,
            &out_layout, s->out_sample_fmt,   s->out_sample_rate,
            &in_layout,  s->codec_ctx->sample_fmt, s->codec_ctx->sample_rate,
            0, nullptr);
        av_channel_layout_uninit(&in_layout);
        av_channel_layout_uninit(&out_layout);
        if (ret < 0) { return report(ret, "swr_alloc_set_opts2"); }
        ret = swr_init(s->swr_ctx);
        if (ret < 0) { return report(ret, "swr_init"); }
    }

    s->packet = av_packet_alloc();
    s->frame  = av_frame_alloc();
    if (s->packet == nullptr || s->frame == nullptr) {
        if (error_out) *error_out = "av_packet_alloc / av_frame_alloc failed (OOM)";
        free_session(s);
        return nullptr;
    }

    if (!s->is_dsd && !forced_pcm_from_dsd) {
        if (s->codec_ctx->bits_per_raw_sample > 0) {
            // Lossless decoders (FLAC, ALAC, PCM WAV) always populate this.
            s->source_bit_depth = s->codec_ctx->bits_per_raw_sample;
        } else if (s->out_android_encoding == ENCODING_PCM_16BIT) {
            // Lossy codecs (MP3, AAC, Vorbis, Opus) decode to AV_SAMPLE_FMT_FLTP
            // internally, but their quality ceiling is 16-bit — choose_output_format
            // already mapped them to AV_SAMPLE_FMT_S16 / ENCODING_PCM_16BIT.
            // Deriving source_bit_depth from bytes_per_sample_for(FLTP)*8 = 32 here
            // would incorrectly report 32-bit depth and disable the S16→S32 MSB
            // expansion in the USB pump, causing 2× speed playback (chipmunk effect).
            s->source_bit_depth = 16;

            // Log the normalization so MP3/AAC format resolution is visible.
            __android_log_print(ANDROID_LOG_INFO, "FfmpegDecoder",
                "Normalized format to S16: %d bytes per frame (lossy codec FLTP→S16 via SwrContext)",
                av_get_bytes_per_sample(AV_SAMPLE_FMT_S16) * s->out_channels);
        } else {
            // Non-lossy but bits_per_raw_sample absent (uncommon): derive from output
            // sample format width.  This covers edge cases like unsigned 8-bit PCM.
            s->source_bit_depth = bytes_per_sample_for(s->out_sample_fmt) * 8;
        }
    }

    s->bitrate_bps = (s->codec_ctx->bit_rate > 0)
        ? s->codec_ctx->bit_rate
        : s->fmt_ctx->bit_rate;

    const DecodeLoad load = classify_decode_load(
            is_dsd_codec(s->codec_ctx->codec_id), s->codec_ctx->sample_rate);
    bind_current_thread_for_decode_load(load);

    return s;
}

// ── ffmpeg_session_get_format ─────────────────────────────────────────────────

/**
 * Fill `fmt_out` from the negotiated output shape of an open session.
 *
 * Called once by FfmpegAudioDecoder::open() to cache the DecoderFormat so
 * subsequent format() calls have O(1) cost.
 *
 * @param session  Non-null open session produced by ffmpeg_session_open().
 * @param fmt_out  Output structure to populate.
 */
extern "C"
void ffmpeg_session_get_format(
        const FfmpegSession *session,
        DecoderFormat        *fmt_out) noexcept
{
    if (session == nullptr || fmt_out == nullptr) return;
    fmt_out->sample_rate_hz       = session->out_sample_rate;
    fmt_out->channel_count        = session->out_channels;
    fmt_out->bit_depth            = session->source_bit_depth;
    fmt_out->bytes_per_sample     = session->out_bytes_per_sample;
    fmt_out->android_pcm_encoding = session->out_android_encoding;
    fmt_out->is_dsd               = session->is_dsd;
    fmt_out->dsd_rate_multiplier  = session->dsd_rate_multiplier;
}

// ── ffmpeg_session_read_pcm ───────────────────────────────────────────────────

/**
 * Decode and write PCM bytes into a caller-owned buffer.
 *
 * Behavioural contract is identical to nativeReadNextBuffer() but without
 * JNI DirectByteBuffer plumbing, without AudioTrack output, and without
 * cross-JNI exception propagation.  The caller owns `dst` entirely.
 *
 * @param session  Open session.
 * @param dst      Caller-owned output buffer.
 * @param dst_cap  Maximum bytes to write; must be > 0.
 * @return         Bytes written, kDecoderEof (0), or kDecoderRecoverableError (-1).
 */
extern "C"
int ffmpeg_session_read_pcm(
        FfmpegSession *session,
        uint8_t       *dst,
        int            dst_cap) noexcept
{
    if (session == nullptr || dst == nullptr || dst_cap <= 0 || session->is_dsd) {
        return kDecoderRecoverableError;
    }

    // Drain spill buffer first.
    if (session->spill_len > 0) {
        int written = spill_drain(session, dst, dst_cap);
        if (written > 0) return written;
    }

    while (true) {
        int ret = av_read_frame(session->fmt_ctx, session->packet);
        if (ret == AVERROR_EOF) {
            avcodec_send_packet(session->codec_ctx, nullptr);
            bool any_frame = false;
            while (true) {
                int r = avcodec_receive_frame(session->codec_ctx, session->frame);
                if (r == AVERROR_EOF || r == AVERROR(EAGAIN)) break;
                if (r < 0) break;
                any_frame = true;
                if (!frame_to_spill(session, session->frame)) {
                    av_frame_unref(session->frame);
                    return kDecoderRecoverableError;
                }
                av_frame_unref(session->frame);
            }
            if (session->spill_len > 0) {
                int written = spill_drain(session, dst, dst_cap);
                return written > 0 ? written : kDecoderEof;
            }
            return any_frame ? kDecoderRecoverableError : kDecoderEof;
        }
        if (ret < 0) {
            av_packet_unref(session->packet);
            return kDecoderRecoverableError;
        }
        if (session->packet->stream_index != session->audio_stream_index) {
            av_packet_unref(session->packet);
            continue;
        }
        int send_ret = avcodec_send_packet(session->codec_ctx, session->packet);
        av_packet_unref(session->packet);
        if (send_ret < 0 && send_ret != AVERROR(EAGAIN)) {
            return kDecoderRecoverableError;
        }
        bool produced_any = false;
        while (true) {
            int r = avcodec_receive_frame(session->codec_ctx, session->frame);
            if (r == AVERROR(EAGAIN) || r == AVERROR_EOF) break;
            if (r < 0) {
                av_frame_unref(session->frame);
                return kDecoderRecoverableError;
            }
            produced_any = true;
            if (!frame_to_spill(session, session->frame)) {
                av_frame_unref(session->frame);
                return kDecoderRecoverableError;
            }
            av_frame_unref(session->frame);
        }
        if (produced_any && session->spill_len > 0) {
            int written = spill_drain(session, dst, dst_cap);
            if (written > 0) return written;
        }
    }
}

// ── ffmpeg_session_read_dsd ───────────────────────────────────────────────────

/**
 * Read raw MSB-first DSD bytes into a caller-owned buffer.
 *
 * Mirrors nativeReadNextDsdBuffer() without the JNI byteArray overhead.
 * Valid only for sessions where is_dsd == true.
 *
 * @param session  Open DSD session.
 * @param dst      Caller-owned output buffer.
 * @param dst_cap  Maximum bytes to write.
 * @return         Bytes written, kDecoderEof (0), or kDecoderRecoverableError (-1).
 */
extern "C"
int ffmpeg_session_read_dsd(
        FfmpegSession *session,
        uint8_t       *dst,
        int            dst_cap) noexcept
{
    if (session == nullptr || dst == nullptr || dst_cap <= 0 || !session->is_dsd) {
        return kDecoderRecoverableError;
    }

    if (session->spill_len > 0) {
        const int written = spill_drain(session, dst, dst_cap);
        if (written > 0) return written;
    }

    while (true) {
        const int ret = av_read_frame(session->fmt_ctx, session->packet);
        if (ret == AVERROR_EOF) {
            const int written = spill_drain(session, dst, dst_cap);
            return written > 0 ? written : kDecoderEof;
        }
        if (ret < 0) {
            av_packet_unref(session->packet);
            return kDecoderRecoverableError;
        }
        if (session->packet->stream_index != session->audio_stream_index) {
            av_packet_unref(session->packet);
            continue;
        }
        const int spill_start = session->spill_len;
        const bool appended = append_normalized_dsd_packet_to_spill(session, session->packet);
        if (appended) {
            trim_dsd_spill_to_exact_seek(session, session->packet, spill_start);
        }
        av_packet_unref(session->packet);
        if (!appended) return kDecoderRecoverableError;

        if (session->spill_len > 0) {
            const int written = spill_drain(session, dst, dst_cap);
            return written > 0 ? written : kDecoderRecoverableError;
        }
    }
}

// ── ffmpeg_session_seek ────────────────────────────────────────────────────────

/**
 * Seek to `position_us` microseconds and flush decoder + lavfi state.
 *
 * @param session     Open session.
 * @param position_us Target position in microseconds.
 * @return            true on success; false on failure.
 */
extern "C"
bool ffmpeg_session_seek(
        FfmpegSession *session,
        int64_t        position_us) noexcept
{
    if (session == nullptr || session->fmt_ctx == nullptr) return false;

    AVStream *stream = session->fmt_ctx->streams[session->audio_stream_index];
    const int64_t target = av_rescale_q(
        position_us,
        AVRational{1, AV_TIME_BASE},
        stream->time_base);

    const int ret = av_seek_frame(
        session->fmt_ctx, session->audio_stream_index,
        target, AVSEEK_FLAG_BACKWARD);

    if (ret < 0) {
        char errbuf[AV_ERROR_MAX_STRING_SIZE];
        av_strerror(ret, errbuf, sizeof(errbuf));
        ALOGE("ffmpeg_session_seek: av_seek_frame failed: %s", errbuf);
        return false;
    }

    avcodec_flush_buffers(session->codec_ctx);
    session->spill_len = 0;
    session->exact_dsd_seek_target_us =
        session->is_dsd ? position_us : AV_NOPTS_VALUE;

    // Flush the lavfi DSD-prep graph so no stale frames remain from before the
    // seek position.  Rebuilding is the safest approach because libavfilter IIR
    // filters (biquad LPF, alimiter) retain internal state across frames.
    if (session->filter_graph != nullptr) {
        dsd_prep_build(session);
    }

    return true;
}

// ── ffmpeg_session_close ───────────────────────────────────────────────────────

/**
 * Close and destroy a session, freeing all FFmpeg resources.
 *
 * @param session  Session to destroy.  Passing nullptr is a safe no-op.
 */
extern "C"
void ffmpeg_session_close(FfmpegSession *session) noexcept
{
    free_session(session);
}

// ─────────────────────────────────────────────────────────────────────────────
// JNI surface
// ─────────────────────────────────────────────────────────────────────────────

extern "C"
JNIEXPORT jlong JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_native_1_FFmpegDecoder_nativeOpen(
    JNIEnv *env, jclass /*clazz*/, jstring jpath, jboolean jforce_pcm) {
    if (jpath == nullptr) {
        throw_decoder_exception(env, AVERROR(EINVAL), "path must not be null");
        return 0;
    }

    const char *path = env->GetStringUTFChars(jpath, nullptr);
    if (path == nullptr) return 0;

    std::string error;
    FfmpegSession *session = ffmpeg_session_open(
            path, jforce_pcm == JNI_TRUE, &error);
    env->ReleaseStringUTFChars(jpath, path);

    if (session == nullptr) {
        throw_decoder_exception(
                env, AVERROR_EXTERNAL,
                error.empty() ? "FFmpeg session open failed" : error.c_str());
        return 0;
    }
    return reinterpret_cast<jlong>(session);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_native_1_FFmpegDecoder_nativeClose(
    JNIEnv * /*env*/, jclass /*clazz*/, jlong handle) {
    ffmpeg_session_close(reinterpret_cast<Session *>(handle));
}


extern "C"
JNIEXPORT jint JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_native_1_FFmpegDecoder_nativeGetSampleRate(
    JNIEnv *, jclass, jlong h) {
    auto *s = reinterpret_cast<Session *>(h); return s ? s->out_sample_rate : 0;
}
extern "C"
JNIEXPORT jint JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_native_1_FFmpegDecoder_nativeGetChannelCount(
    JNIEnv *, jclass, jlong h) {
    auto *s = reinterpret_cast<Session *>(h); return s ? s->out_channels : 0;
}
extern "C"
JNIEXPORT jint JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_native_1_FFmpegDecoder_nativeGetSourceBitDepth(
    JNIEnv *, jclass, jlong h) {
    auto *s = reinterpret_cast<Session *>(h); return s ? s->source_bit_depth : 0;
}
extern "C"
JNIEXPORT jint JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_native_1_FFmpegDecoder_nativeGetAndroidPcmEncoding(
    JNIEnv *, jclass, jlong h) {
    auto *s = reinterpret_cast<Session *>(h); return s ? s->out_android_encoding : 0;
}
extern "C"
JNIEXPORT jint JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_native_1_FFmpegDecoder_nativeGetBytesPerSample(
    JNIEnv *, jclass, jlong h) {
    auto *s = reinterpret_cast<Session *>(h); return s ? s->out_bytes_per_sample : 0;
}
extern "C"
JNIEXPORT jlong JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_native_1_FFmpegDecoder_nativeGetDurationUs(
    JNIEnv *, jclass, jlong h) {
    auto *s = reinterpret_cast<Session *>(h);
    if (s == nullptr || s->fmt_ctx == nullptr) return 0;
    if (s->fmt_ctx->duration == AV_NOPTS_VALUE) return 0;
    return static_cast<jlong>(s->fmt_ctx->duration);
}
extern "C"
JNIEXPORT jlong JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_native_1_FFmpegDecoder_nativeGetBitrateBps(
    JNIEnv *, jclass, jlong h) {
    auto *s = reinterpret_cast<Session *>(h);
    return s ? static_cast<jlong>(s->bitrate_bps) : 0;
}
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_native_1_FFmpegDecoder_nativeIsDsd(
    JNIEnv *, jclass, jlong h) {
    auto *s = reinterpret_cast<Session *>(h);
    return s && s->is_dsd ? JNI_TRUE : JNI_FALSE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_native_1_FFmpegDecoder_nativeIsResampledDsd(
    JNIEnv *, jclass, jlong h) {
    auto *s = reinterpret_cast<Session *>(h);
    return s && s->is_resampled_dsd ? JNI_TRUE : JNI_FALSE;
}
extern "C"
JNIEXPORT jint JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_native_1_FFmpegDecoder_nativeGetDsdRate(
    JNIEnv *, jclass, jlong h) {
    auto *s = reinterpret_cast<Session *>(h);
    return s ? s->dsd_rate_multiplier : 0;
}
extern "C"
JNIEXPORT jstring JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_native_1_FFmpegDecoder_nativeGetCodecName(
    JNIEnv *env, jclass, jlong h) {
    auto *s = reinterpret_cast<Session *>(h);
    if (s == nullptr || s->codec_ctx == nullptr || s->codec_ctx->codec == nullptr) {
        return env->NewStringUTF("");
    }
    return env->NewStringUTF(s->codec_ctx->codec->name);
}
extern "C"
JNIEXPORT jstring JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_native_1_FFmpegDecoder_nativeGetCodecProfileName(
    JNIEnv *env, jclass, jlong h) {
    auto *s = reinterpret_cast<Session *>(h);
    if (s == nullptr || s->codec_ctx == nullptr) {
        return env->NewStringUTF("");
    }
    const char *profile_name = avcodec_profile_name(s->codec_ctx->codec_id, s->codec_ctx->profile);
    return env->NewStringUTF(profile_name != nullptr ? profile_name : "");
}
extern "C"
JNIEXPORT jstring JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_native_1_FFmpegDecoder_nativeGetContainerName(
    JNIEnv *env, jclass, jlong h) {
    auto *s = reinterpret_cast<Session *>(h);
    if (s == nullptr || s->container_name.empty()) {
        return env->NewStringUTF("");
    }
    return env->NewStringUTF(s->container_name.c_str());
}

// ─────────────────────────────────────────────────────────────────────────────
// Hot path — decode up to (dstCap) bytes into a caller-owned DirectByteBuffer.
//
//   The buffer MUST be a java.nio.DirectByteBuffer allocated with
//   ByteBuffer.allocateDirect() on the Kotlin side. We obtain a raw C pointer
//   via GetDirectBufferAddress — no JVM copy is ever performed.
//
//   Safety: GetDirectBufferCapacity is checked against dstCap and the decoded
//   chunk size so we can never overrun the buffer regardless of what the
//   Kotlin layer passes in.
//
//   Pause / CPU utilisation contract:
//     This function is ONLY called when playWhenReady == true on the Kotlin
//     side (BitPerfectPlaybackEngine.writeLoopIteration). When the engine is
//     paused the Kotlin write loop stops posting new iterations to the Handler,
//     so nativeReadNextBuffer is never called during a pause. There is therefore
//     NO blocking wait, NO busy-wait, and NO sleep loop inside this function —
//     the native thread is simply never entered while the player is paused. CPU
//     utilisation during pause is strictly 0% from this function's perspective.
//
//   Return value:
//     > 0   bytes written (may be less than capacity)
//     = 0   end of stream
//     < 0   recoverable decode error (caller may retry)
// ─────────────────────────────────────────────────────────────────────────────
extern "C"
JNIEXPORT jint JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_native_1_FFmpegDecoder_nativeReadNextBuffer(
    JNIEnv *env, jclass /*clazz*/, jlong handle, jobject jbuffer, jint dst_cap) {

    auto *s = reinterpret_cast<Session *>(handle);
    if (s == nullptr || jbuffer == nullptr || dst_cap <= 0 || s->is_dsd) return READ_RECOVERABLE_ERROR;

    // Zero-copy: obtain a direct pointer into the JVM-allocated DirectByteBuffer.
    // GetDirectBufferAddress returns nullptr for non-direct or already-GC'd buffers.
    void *raw_ptr = env->GetDirectBufferAddress(jbuffer);
    if (raw_ptr == nullptr) {
        ALOGE("nativeReadNextBuffer: GetDirectBufferAddress returned null — buffer must be direct");
        return READ_RECOVERABLE_ERROR;
    }

    // CRITICAL SAFETY: clamp the usable capacity to the smaller of what the
    // Kotlin caller requested and what the buffer can actually hold. This
    // prevents a C-level buffer overrun even if the two sides get out of sync.
    jlong buffer_capacity = env->GetDirectBufferCapacity(jbuffer);
    if (buffer_capacity <= 0) return READ_RECOVERABLE_ERROR;
    int actual_cap = (dst_cap < static_cast<int>(buffer_capacity))
                         ? dst_cap
                         : static_cast<int>(buffer_capacity);

    return ffmpeg_session_read_pcm(
            s, static_cast<uint8_t *>(raw_ptr), actual_cap);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_native_1_FFmpegDecoder_nativeReadNextDsdBuffer(
    JNIEnv *env, jclass /*clazz*/, jlong handle, jbyteArray jbuffer) {

    auto *s = reinterpret_cast<Session *>(handle);
    if (s == nullptr || jbuffer == nullptr || !s->is_dsd) return READ_RECOVERABLE_ERROR;

    const jsize actual_cap = env->GetArrayLength(jbuffer);
    if (actual_cap <= 0) return READ_RECOVERABLE_ERROR;

    jbyte *raw_dst = env->GetByteArrayElements(jbuffer, nullptr);
    if (raw_dst == nullptr) return READ_RECOVERABLE_ERROR;
    const int result = ffmpeg_session_read_dsd(
            s, reinterpret_cast<uint8_t *>(raw_dst), actual_cap);
    env->ReleaseByteArrayElements(jbuffer, raw_dst, 0);
    return result;
}

// ─────────────────────────────────────────────────────────────────────────────
// Seek
// ─────────────────────────────────────────────────────────────────────────────
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_native_1_FFmpegDecoder_nativeSeek(
    JNIEnv *env, jclass /*clazz*/, jlong handle, jlong position_us) {

    const bool succeeded = ffmpeg_session_seek(
            reinterpret_cast<Session *>(handle),
            static_cast<int64_t>(position_us));
    if (!succeeded) {
        throw_decoder_exception(env, AVERROR_EXTERNAL, "FFmpeg session seek failed");
    }
    return succeeded ? JNI_TRUE : JNI_FALSE;
}

/**
 * Returns true when the lavfi+soxr pipeline (VHQ precision=33, linear-phase)
 * is handling the DSD decimation for this session (Tier-3 DSD fallback path).
 *
 * The lavfi filter graph includes `aresample=osr=88200:resampler=soxr:precision=33`
 * so soxr runs entirely inside FFmpeg's filter graph — no external soxr C-API.
 *
 * The Kotlin/AudioTelemetry layer uses this to report accurate pipeline details:
 *   Resampler : soxr (lavfi / VHQ precision=33)
 *   Quality   : VHQ (Very High Quality)
 *   Precision : 33-bit / Linear Phase
 *
 * @return JNI_TRUE when filter_graph is active (lavfi+soxr path); JNI_FALSE otherwise.
 */
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_native_1_FFmpegDecoder_nativeIsUsingSoxrIntegrated(
    JNIEnv *, jclass, jlong handle) {
    auto *s = reinterpret_cast<Session *>(handle);
    return (s != nullptr && s->filter_graph != nullptr && s->is_resampled_dsd) ? JNI_TRUE : JNI_FALSE;
}

/**
 * Returns the ReplayGain track gain (in dB) extracted lazily from the file's
 * AVDictionary only when the Hi-Res Dynamic Remaster path asks for it.
 *
 * Only meaningful for CD-quality lossless PCM sessions (ENCODING_PCM_16BIT).
 * For all other encoding paths (DSD, DSD PCM fallback, 32-bit, float) the
 * REMASTER_DEFAULT_GAIN_DB (−3.0 dB) is returned immediately and no metadata
 * scan occurs.
 *
 * The Kotlin layer queries this value only when Hi-Res Dynamic Remaster is the
 * active processing owner, then passes it to SueBridge.nativeCreate so
 * build_hires_remaster_chain can drive the volume lavfi stage with the correct gain.
 *
 * @return Peak-derived headroom gain in dB, or REMASTER_DEFAULT_GAIN_DB
 *         (−3.0 dB) when no REPLAYGAIN_TRACK_PEAK tag was found.
 */
extern "C"
JNIEXPORT jfloat JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_native_1_FFmpegDecoder_nativeGetReplayGainDb(
    JNIEnv *, jclass, jlong handle) {
    auto *s = reinterpret_cast<Session *>(handle);
    if (s == nullptr) return REMASTER_DEFAULT_GAIN_DB;
    if (s->is_dsd || s->is_resampled_dsd || s->out_android_encoding != ENCODING_PCM_16BIT) {
        return REMASTER_DEFAULT_GAIN_DB;
    }
    if (!s->replaygain_resolved) {
        s->replaygain_db = extract_replaygain_db(s->fmt_ctx, s->audio_stream_index);
        s->replaygain_resolved = true;
    }
    return s->replaygain_db;
}
