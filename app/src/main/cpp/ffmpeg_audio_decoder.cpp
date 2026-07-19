// ─────────────────────────────────────────────────────────────────────────────
// ffmpeg_audio_decoder.cpp
//
// Step 15 — FfmpegAudioDecoder implementation.
//
// This file implements IAudioDecoder using a thin C++ wrapper around the
// existing Session decode pipeline from ffmpeg_bridge.cpp.
//
// ### Coupling strategy
//
// Rather than duplicating the complex session machinery (spill buffer,
// SwrContext, lavfi DSD-prep graph, CPU affinity), this wrapper calls the
// C++ internal API exposed by ffmpeg_bridge.cpp via the "ffmpeg_session_*"
// non-JNI entry points added in the same Step 15 patch:
//
//   ffmpeg_session_open()       — equivalent to nativeOpen, returns FfmpegSession*
//   ffmpeg_session_read_pcm()   — equivalent to nativeReadNextBuffer, no JNI
//   ffmpeg_session_read_dsd()   — equivalent to nativeReadNextDsdBuffer, no JNI
//   ffmpeg_session_seek()       — equivalent to nativeSeek, no JNI
//   ffmpeg_session_close()      — equivalent to nativeClose, no JNI
//   ffmpeg_session_get_format() — fills a DecoderFormat from session fields
//
// These functions share the Session implementation verbatim with the JNI
// bridge — zero code duplication.
//
// ### What is stripped vs. retained
//
//   STRIPPED:
//     • All JNI plumbing (JNIEnv*, jlong handles, jbyteArray, jobject)
//     • AudioTrack / OS audio output of any kind
//     • ThrowNew / exception propagation across the JNI boundary
//
//   RETAINED:
//     • FFmpeg avformat / avcodec decode pipeline
//     • SwrContext packed interleaved conversion
//     • DSD-prep lavfi filter graph (volume/LPF/alimiter/soxr VHQ)
//     • Spill buffer for partial-frame accumulation
//     • Source-depth-preserving output format selection
//     • CPU affinity hint (bind_current_thread_for_decode_load)
// ─────────────────────────────────────────────────────────────────────────────

#include "ffmpeg_audio_decoder.h"

#include <android/log.h>
#include <cstring>
#include <memory>
#include <string>

// ── Logging ───────────────────────────────────────────────────────────────────

#define FAD_TAG   "FfmpegAudioDecoder"
#define FADLOGD(...) __android_log_print(ANDROID_LOG_DEBUG, FAD_TAG, __VA_ARGS__)
#define FADLOGE(...) __android_log_print(ANDROID_LOG_ERROR, FAD_TAG, __VA_ARGS__)

// ─────────────────────────────────────────────────────────────────────────────
// FfmpegAudioDecoder — private constructor
// ─────────────────────────────────────────────────────────────────────────────

FfmpegAudioDecoder::FfmpegAudioDecoder(FfmpegSession *session) noexcept
    : session_(session)
{}

// ─────────────────────────────────────────────────────────────────────────────
// FfmpegAudioDecoder::open — factory
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Open and initialise an FFmpeg decode session.
 *
 * Delegates all session lifecycle work to `ffmpeg_session_open()`.  On success
 * the format is cached immediately so format() is O(1) for the pump thread.
 *
 * No JNI types or environment pointer are required — this function is safe to
 * call from any C++ thread, including the DecoderToRingBridge pump thread.
 *
 * @param path       Absolute filesystem path to the audio file.
 * @param force_pcm  Activate the Tier-3 DSD → PCM lavfi+soxr VHQ path when the
 *                   source is DSD and a USB DAC that supports DoP / native DSD
 *                   is NOT connected.
 * @param error_out  Optional human-readable failure description.
 * @return           Owning unique_ptr to a ready decoder, or nullptr on failure.
 */
std::unique_ptr<FfmpegAudioDecoder> FfmpegAudioDecoder::open(
        const char  *path,
        bool         force_pcm,
        std::string *error_out) noexcept
{
    if (path == nullptr || path[0] == '\0') {
        if (error_out) *error_out = "path must not be null or empty";
        return nullptr;
    }

    std::string internal_error;
    FfmpegSession *raw = ffmpeg_session_open(path, force_pcm, &internal_error);
    if (raw == nullptr) {
        if (error_out) *error_out = internal_error;
        FADLOGE("open: ffmpeg_session_open failed — %s", internal_error.c_str());
        return nullptr;
    }

    // Allocate the wrapper outside the session-open scope to keep ownership
    // clear: if the wrapper allocation fails, the session is closed cleanly.
    auto *decoder = new (std::nothrow) FfmpegAudioDecoder(raw);
    if (decoder == nullptr) {
        ffmpeg_session_close(raw);
        if (error_out) *error_out = "out of memory allocating FfmpegAudioDecoder";
        FADLOGE("open: out of memory allocating FfmpegAudioDecoder wrapper");
        return nullptr;
    }

    // Cache the format once so IAudioDecoder::format() is lock-free on the
    // pump thread hot path.
    ffmpeg_session_get_format(raw, &decoder->format_);

    FADLOGD("open: session ready — %dHz %dch bit_depth=%d is_dsd=%s",
            decoder->format_.sample_rate_hz,
            decoder->format_.channel_count,
            decoder->format_.bit_depth,
            decoder->format_.is_dsd ? "yes" : "no");

    return std::unique_ptr<FfmpegAudioDecoder>(decoder);
}

// ─────────────────────────────────────────────────────────────────────────────
// Destructor
// ─────────────────────────────────────────────────────────────────────────────

FfmpegAudioDecoder::~FfmpegAudioDecoder()
{
    // Close and free all FFmpeg resources including the spill buffer,
    // SwrContext, lavfi graph, AVFormatContext, and AVCodecContext.
    ffmpeg_session_close(session_);
    session_ = nullptr;
    FADLOGD("~FfmpegAudioDecoder: session closed");
}

// ─────────────────────────────────────────────────────────────────────────────
// IAudioDecoder implementation
// ─────────────────────────────────────────────────────────────────────────────

const DecoderFormat &FfmpegAudioDecoder::format() const noexcept
{
    return format_;
}

/**
 * Decode and write up to `dst_cap` interleaved PCM bytes into `dst`.
 *
 * Internally delegates to `ffmpeg_session_read_pcm()`, which drains the
 * session's spill buffer before issuing new avcodec_receive_frame() calls.
 * The lavfi DSD-prep filter graph is transparent to this call — the spill
 * buffer already holds 88 200 Hz FLT frames after lavfi processing.
 *
 * The pump thread calls this in a tight loop while the ring buffer has space.
 * The function is allocation-free after session open.
 */
int FfmpegAudioDecoder::read_pcm(uint8_t *dst, int dst_cap) noexcept
{
    if (session_ == nullptr || dst == nullptr || dst_cap <= 0) {
        return kDecoderRecoverableError;
    }
    // Sessions that were opened as native DSD pass-through must use read_dsd().
    // Calling read_pcm() on them would drain DSD bytes as if they were PCM —
    // guard against accidental misuse here.
    if (format_.is_dsd) {
        FADLOGE("read_pcm: called on a native-DSD session — use read_dsd()");
        return kDecoderRecoverableError;
    }
    return ffmpeg_session_read_pcm(session_, dst, dst_cap);
}

/**
 * Read up to `dst_cap` MSB-first DSD bytes into `dst`.
 *
 * Delegates to `ffmpeg_session_read_dsd()`, which reads raw AVPacket payloads
 * and normalises bit order to MSB-first.  The result is a canonical DSD stream
 * ready for DoP framing or native-DSD isochronous delivery.
 *
 * Only valid for sessions opened on a DSD source with force_pcm = false.
 */
int FfmpegAudioDecoder::read_dsd(uint8_t *dst, int dst_cap) noexcept
{
    if (session_ == nullptr || dst == nullptr || dst_cap <= 0) {
        return kDecoderRecoverableError;
    }
    if (!format_.is_dsd) {
        FADLOGE("read_dsd: called on a non-DSD (PCM) session — use read_pcm()");
        return kDecoderRecoverableError;
    }
    return ffmpeg_session_read_dsd(session_, dst, dst_cap);
}

/**
 * Seek to `position_us` in the source stream.
 *
 * After a successful seek the spill buffer and any lavfi graph delay lines are
 * flushed, guaranteeing that the next read delivers frames from the new
 * position without any pre-seek residue.
 */
bool FfmpegAudioDecoder::seek(int64_t position_us) noexcept
{
    if (session_ == nullptr) return false;
    return ffmpeg_session_seek(session_, position_us);
}
