// ─────────────────────────────────────────────────────────────────────────────
// audio_analysis_bridge.cpp
//
// JNI implementation for AudioAnalysisBridge.kt and
// AudioIntegralAnalysisBridge.kt — a measurement-only lavfi bridge that reports
// signal features for decoded PCM.
//
// This bridge NEVER touches the playback data path. It builds its own graph,
// consumes PCM the caller has already decoded on @IoDispatcher, and returns
// numbers. It applies no gain, no resampling, no dithering, and nothing it
// produces is fed back into any sink.
//
// Two measurement modes share the whole session machinery and differ only in
// the filter chain and the aggregate they build.
//
// Stationary ("Class S") mode — a few sampled windows per track:
//
//   abuffer
//     -> aformat=sample_fmts=flt
//     -> aspectralstats(metadata on every output frame)
//     -> astats(metadata=1, cumulative)
//     -> abuffersink
//
// Integral ("Class I") mode — every sample of the stream, once:
//
//   abuffer
//     -> aformat=sample_fmts=dbl
//     -> astats(metadata=1, cumulative)
//     -> ebur128(metadata=1, peak=sample+true)
//     -> abuffersink
//
// Every stats filter in both chains is pass-through: they publish their
// measurements in the output frame's AVDictionary and forward the samples
// untouched, so a single drain loop can read the metadata AND accumulate the
// raw sample statistics from the same frame.
//
// Integral mode works in float64 because that is the only format ebur128
// accepts; putting the conversion at the head of the chain rather than letting
// the graph insert one mid-chain keeps the sink format known and lets the
// sample pass read one width. Neither mode's format choice has any bearing on
// playback: nothing measured here is ever written to a sink.
//
// ### Filter and metadata names — verified, not assumed
//
// Every option and key below was checked against the FFmpeg 7.1.4
// libavfilter.so actually shipped in app/src/main/jniLibs/ (string table of
// libavfilter.so, arm64-v8a):
//
//   filters          : "aspectralstats", "astats", "aformat", "abuffer",
//                      "abuffersink"
//   aspectralstats   : "win_size", "overlap", "win_func", "measure";
//                      measure constants "centroid", "rolloff", "slope"
//                      (also present: mean, variance, spread, skewness,
//                      kurtosis, entropy, flatness, crest, flux, decrease)
//   astats           : "metadata", "reset", "measure_perchannel",
//                      "measure_overall"; measure constants "DC_offset",
//                      "Noise_floor", "RMS_level", "Peak_level", "Flat_factor"
//   ebur128          : "metadata" ("inject metadata in the filtergraph"),
//                      "peak" ("set peak mode") with constants "none",
//                      "sample" ("enable peak-sample mode") and "true"
//                      ("enable true-peak mode"), "framelog" ("force frame
//                      logging level") with constant "quiet"
//   metadata key fmt : "lavfi.aspectralstats.%d.%s"  (channels are 1-based)
//                      "lavfi.astats.%s"             (overall, "Overall." prefix)
//                      "lavfi.r128.I", "lavfi.r128.M", "lavfi.r128.S",
//                      "lavfi.r128.LRA", "lavfi.r128.sample_peak",
//                      "lavfi.r128.true_peak", "lavfi.r128.true_peaks_ch%d"
//
// ebur128 publishes that dictionary once per completed 100 ms block with the
// running values, so — exactly like astats at reset=0 — the last snapshot seen
// before end of stream is the whole-stream figure. Its peak keys carry a linear
// amplitude, not dB: the filter's own end-of-stream log is what applies
// 20·log10 to the same fields.
//
// ### Statistics deliberately NOT taken from lavfi
//
// Mid/side energy and inter-channel correlation are not exposed as metadata by
// any filter in this build. `astats` measures channels in isolation, and
// `aphasemeter` — the only filter that reports a correlation — publishes a
// sign-correlation meter value and opens a second, video output pad that does
// not belong in an audio-only measurement graph. They are therefore derived
// exactly from the float samples this graph already hands us; see
// audio_analysis_aggregator.h.
//
// The sample peak, the clipping ratio and the flat-top run lengths of the
// integral mode are counted from samples for the reasons set out in
// audio_integral_aggregator.h — chiefly that `astats`' `Abs_Peak_count` counts
// samples at the *observed* maximum rather than at full scale. astats is still
// in the integral chain, as the ticket specifies, and its `Overall.Peak_level`
// and `Overall.Flat_factor` are logged beside the counted values so a drift
// between the graph and the sample pass is visible rather than silent.
//
// ### Threading
//
// No internal locking. Every call on a handle must come from the one thread
// that opened it — the analysis coroutine on @IoDispatcher, never the playback
// engine's audio HandlerThread.
// ─────────────────────────────────────────────────────────────────────────────

#include <jni.h>
#include <android/log.h>

#include <cstdint>
#include <cstdio>
#include <cstring>
#include <limits>
#include <memory>
#include <new>
#include <string>

#include "audio_analysis_aggregator.h"
#include "audio_integral_aggregator.h"

extern "C" {
#include <libavfilter/avfilter.h>
#include <libavfilter/buffersrc.h>
#include <libavfilter/buffersink.h>
#include <libavutil/avstring.h>
#include <libavutil/channel_layout.h>
#include <libavutil/dict.h>
#include <libavutil/error.h>
#include <libavutil/frame.h>
#include <libavutil/samplefmt.h>
}

#define ANALYSIS_TAG "AudioAnalysis"
#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, ANALYSIS_TAG, __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN,  ANALYSIS_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, ANALYSIS_TAG, __VA_ARGS__)

// ─── Android AudioFormat constants (must match AudioAnalysisBridge.kt) ───────
static constexpr int ENCODING_PCM_16BIT = 2;
static constexpr int ENCODING_PCM_FLOAT = 4;
static constexpr int ENCODING_PCM_32BIT = 22;

// ─── Error sentinels (must match AudioAnalysisBridge.kt) ─────────────────────
//
// nativeOpen reports failure with 0L, exactly like SueBridge.nativeCreate, so
// no negative handle can ever reach Kotlin and handle validation stays a plain
// null check.
static constexpr jint ANALYSIS_ERR_INVALID_HANDLE = -1;
static constexpr jint ANALYSIS_ERR_INVALID_ARGS   = -2;
static constexpr jint ANALYSIS_ERR_GRAPH_FAILED   = -3;

// ─── Analysis window shape ───────────────────────────────────────────────────
//
// 2048 samples at 44.1 kHz is ~46 ms and ~21.5 Hz of bin resolution: fine
// enough to place a lossy codec's low-pass cutoff, coarse enough that a few
// seconds of audio still average over dozens of windows. 50 % overlap is the
// aspectralstats default and keeps a transient from landing on a window edge.
static constexpr int   kSpectralWindowSize   = 2048;
static constexpr float kSpectralWindowOverlap = 0.5F;

static thread_local std::string g_last_init_error;

static void set_last_init_error(const std::string &message)
{
    g_last_init_error = message;
}

static void clear_last_init_error()
{
    g_last_init_error.clear();
}

// Which family of measurements a session was opened for. The two differ in the
// filter chain, the sink sample format and the aggregate they fill; everything
// else about a session — handle ownership, feeding, draining, teardown — is
// shared.
enum class AnalysisMode {
    Stationary,   // Class S: sampled windows, spectral + stereo relationships
    Integral,     // Class I: the whole stream, loudness + peak + clipping
};

// One measurement session: the graph plus the aggregate built from its output.
struct AnalysisCtx {
    AnalysisMode mode{AnalysisMode::Stationary};

    AVFilterGraph   *filter_graph{nullptr};
    AVFilterContext *src_ctx{nullptr};
    AVFilterContext *sink_ctx{nullptr};
    AVFrame         *in_frame{nullptr};
    AVFrame         *out_frame{nullptr};

    int  channels{0};
    int  sample_rate{0};
    int  input_encoding{0};
    AVSampleFormat input_av_fmt{AV_SAMPLE_FMT_FLT};

    bool finalised{false};        // EOF pushed; no further feeding accepted
    bool warned_non_float{false}; // one-shot guard for an unexpected sink format

    std::unique_ptr<AudioAnalysisAggregator>  aggregator;           // Stationary
    std::unique_ptr<AudioIntegralAggregator>  integral_aggregator;  // Integral
};

// ─── Utility: sample format from the Android encoding constant ───────────────
static AVSampleFormat encoding_to_avsamplefmt(int encoding)
{
    switch (encoding) {
        case ENCODING_PCM_16BIT: return AV_SAMPLE_FMT_S16;
        case ENCODING_PCM_32BIT: return AV_SAMPLE_FMT_S32;
        case ENCODING_PCM_FLOAT: return AV_SAMPLE_FMT_FLT;
        default:                 return AV_SAMPLE_FMT_NONE;
    }
}

static void teardown_filter_graph(AnalysisCtx *ctx)
{
    if (ctx->in_frame)  { av_frame_free(&ctx->in_frame);  }
    if (ctx->out_frame) { av_frame_free(&ctx->out_frame); }
    if (ctx->filter_graph) { avfilter_graph_free(&ctx->filter_graph); }
    ctx->src_ctx      = nullptr;
    ctx->sink_ctx     = nullptr;
    ctx->filter_graph = nullptr;
}

// Writes the measurement chain that sits between abuffer and abuffersink.
//
// `measure=` is restricted to the three spectral statistics this ticket needs;
// aspectralstats computes each requested statistic separately, so asking only
// for what is read keeps the pass cheap. astats measures per channel as well as
// overall because a build that gates accumulation on the per-channel mask would
// otherwise leave the overall keys empty.
static bool build_measurement_chain(char *out, size_t out_size)
{
    const int written = snprintf(
            out, out_size,
            "aformat=sample_fmts=flt,"
            "aspectralstats=win_size=%d:overlap=%.2f:measure=centroid+rolloff+slope,"
            "astats=metadata=1:reset=0"
            ":measure_perchannel=DC_offset+Noise_floor+RMS_level"
            ":measure_overall=DC_offset+Noise_floor",
            kSpectralWindowSize,
            static_cast<double>(kSpectralWindowOverlap));

    return written > 0 && static_cast<size_t>(written) < out_size;
}

// Writes the integral measurement chain.
//
// astats comes first so it measures the stream before ebur128's K-weighting is
// anywhere near it, and ebur128 last so the sink receives the frames it forwards
// untouched. `framelog=quiet` suppresses the filter's own per-block INFO logging,
// which would otherwise print a line every 100 ms of every analysed track.
//
// `peak=sample+true` is what makes the r128 peak keys appear at all; true-peak
// mode is the only way to obtain an inter-sample peak, which cannot be counted
// from the samples themselves.
static bool build_integral_chain(char *out, size_t out_size)
{
    const int written = snprintf(
            out, out_size,
            "aformat=sample_fmts=dbl,"
            "astats=metadata=1:reset=0"
            ":measure_perchannel=Peak_level+Flat_factor"
            ":measure_overall=Peak_level+Flat_factor,"
            "ebur128=metadata=1:peak=sample+true:framelog=quiet");

    return written > 0 && static_cast<size_t>(written) < out_size;
}

static bool build_filter_graph(AnalysisCtx *ctx, const char *chain)
{
    teardown_filter_graph(ctx);

    ctx->filter_graph = avfilter_graph_alloc();
    if (!ctx->filter_graph) {
        set_last_init_error("avfilter_graph_alloc failed");
        ALOGE("build_filter_graph: avfilter_graph_alloc failed");
        return false;
    }

    AVChannelLayout ch_layout{};
    av_channel_layout_default(&ch_layout, ctx->channels);

    char ch_layout_str[64];
    av_channel_layout_describe(&ch_layout, ch_layout_str, sizeof(ch_layout_str));
    av_channel_layout_uninit(&ch_layout);

    char abuffer_args[256];
    snprintf(abuffer_args, sizeof(abuffer_args),
             "sample_rate=%d:sample_fmt=%s:channel_layout=%s",
             ctx->sample_rate,
             av_get_sample_fmt_name(ctx->input_av_fmt),
             ch_layout_str);

    int ret = avfilter_graph_create_filter(
            &ctx->src_ctx,
            avfilter_get_by_name("abuffer"),
            "analysis_src",
            abuffer_args,
            nullptr,
            ctx->filter_graph);
    if (ret < 0) {
        char errbuf[128]; av_strerror(ret, errbuf, sizeof(errbuf));
        set_last_init_error(std::string("abuffer create failed: ") + errbuf);
        ALOGE("build_filter_graph: abuffer create failed: %s", errbuf);
        teardown_filter_graph(ctx);
        return false;
    }

    ret = avfilter_graph_create_filter(
            &ctx->sink_ctx,
            avfilter_get_by_name("abuffersink"),
            "analysis_sink",
            nullptr,
            nullptr,
            ctx->filter_graph);
    if (ret < 0) {
        char errbuf[128]; av_strerror(ret, errbuf, sizeof(errbuf));
        set_last_init_error(std::string("abuffersink create failed: ") + errbuf);
        ALOGE("build_filter_graph: abuffersink create failed: %s", errbuf);
        teardown_filter_graph(ctx);
        return false;
    }

    AVFilterInOut *outputs = avfilter_inout_alloc();
    AVFilterInOut *inputs  = avfilter_inout_alloc();
    if (!outputs || !inputs) {
        set_last_init_error("avfilter_inout_alloc failed");
        ALOGE("build_filter_graph: avfilter_inout_alloc failed");
        avfilter_inout_free(&outputs);
        avfilter_inout_free(&inputs);
        teardown_filter_graph(ctx);
        return false;
    }

    outputs->name       = av_strdup("in");
    outputs->filter_ctx = ctx->src_ctx;
    outputs->pad_idx    = 0;
    outputs->next       = nullptr;

    inputs->name       = av_strdup("out");
    inputs->filter_ctx = ctx->sink_ctx;
    inputs->pad_idx    = 0;
    inputs->next       = nullptr;

    ret = avfilter_graph_parse_ptr(ctx->filter_graph, chain, &inputs, &outputs, nullptr);
    avfilter_inout_free(&outputs);
    avfilter_inout_free(&inputs);

    if (ret < 0) {
        char errbuf[128]; av_strerror(ret, errbuf, sizeof(errbuf));
        set_last_init_error(std::string("avfilter_graph_parse_ptr failed: ") + errbuf +
                            " chain='" + chain + "'");
        ALOGE("build_filter_graph: parse failed: %s (chain='%s')", errbuf, chain);
        teardown_filter_graph(ctx);
        return false;
    }

    ret = avfilter_graph_config(ctx->filter_graph, nullptr);
    if (ret < 0) {
        char errbuf[128]; av_strerror(ret, errbuf, sizeof(errbuf));
        set_last_init_error(std::string("avfilter_graph_config failed: ") + errbuf +
                            " chain='" + chain + "'");
        ALOGE("build_filter_graph: config failed: %s (chain='%s')", errbuf, chain);
        teardown_filter_graph(ctx);
        return false;
    }

    ctx->in_frame  = av_frame_alloc();
    ctx->out_frame = av_frame_alloc();
    if (!ctx->in_frame || !ctx->out_frame) {
        set_last_init_error("av_frame_alloc failed");
        ALOGE("build_filter_graph: av_frame_alloc failed");
        teardown_filter_graph(ctx);
        return false;
    }

    return true;
}

// ─── Metadata harvesting ─────────────────────────────────────────────────────

// Reads one metadata key into `out`; leaves `out` untouched and returns false
// when the key is absent or does not hold a finite number.
static bool read_metadata_double(const AVDictionary *metadata, const char *key, double *out)
{
    const AVDictionaryEntry *entry = av_dict_get(metadata, key, nullptr, 0);
    if (!entry) {
        return false;
    }
    return parse_measured_double(entry->value, out);
}

// Folds one graph output frame into the stationary aggregate: the spectral
// statistics of every channel, the cumulative astats levels, and the raw sample
// energies.
static void harvest_stationary_frame(AnalysisCtx *ctx, const AVFrame *frame)
{
    ctx->aggregator->note_window();

    const AVDictionary *metadata = frame->metadata;

    // aspectralstats publishes one set of keys per channel, 1-based.
    for (int channel = 1; channel <= ctx->channels; channel++) {
        char key[80];
        double centroid = 0.0;
        double rolloff  = 0.0;
        double slope    = 0.0;

        snprintf(key, sizeof(key), "lavfi.aspectralstats.%d.centroid", channel);
        const bool has_centroid = read_metadata_double(metadata, key, &centroid);

        snprintf(key, sizeof(key), "lavfi.aspectralstats.%d.rolloff", channel);
        const bool has_rolloff = read_metadata_double(metadata, key, &rolloff);

        snprintf(key, sizeof(key), "lavfi.aspectralstats.%d.slope", channel);
        const bool has_slope = read_metadata_double(metadata, key, &slope);

        if (!has_centroid && !has_rolloff && !has_slope) {
            continue;   // this frame carries no spectral metadata (warm-up hop)
        }
        ctx->aggregator->add_spectral_channel(
                has_centroid ? centroid : std::numeric_limits<double>::quiet_NaN(),
                has_rolloff  ? rolloff  : std::numeric_limits<double>::quiet_NaN(),
                has_slope    ? slope    : std::numeric_limits<double>::quiet_NaN());
    }

    // astats runs with reset=0, so its overall keys are the running aggregate
    // over everything fed so far; the last frame's values are the track values.
    double noise_floor = std::numeric_limits<double>::quiet_NaN();
    double dc_offset   = std::numeric_limits<double>::quiet_NaN();
    read_metadata_double(metadata, "lavfi.astats.Overall.Noise_floor", &noise_floor);
    read_metadata_double(metadata, "lavfi.astats.Overall.DC_offset", &dc_offset);
    ctx->aggregator->set_level_snapshot(noise_floor, dc_offset);

    // The chain starts with aformat=sample_fmts=flt and no later filter changes
    // the format, so the sink delivers packed interleaved float32.
    if (frame->format != AV_SAMPLE_FMT_FLT) {
        if (!ctx->warned_non_float) {
            ctx->warned_non_float = true;
            ALOGW("harvest_frame: sink delivered format %d, expected FLT — "
                  "stereo energies skipped", frame->format);
        }
        return;
    }
    if (frame->nb_samples > 0 && frame->data[0] != nullptr) {
        ctx->aggregator->add_samples(
                reinterpret_cast<const float *>(frame->data[0]),
                static_cast<size_t>(frame->nb_samples));
    }
}

// Folds one graph output frame into the integral aggregate: the cumulative
// ebur128 loudness snapshot, the corroborating astats levels, and the samples
// the peak / clipping / flat-top counts are taken from.
static void harvest_integral_frame(AnalysisCtx *ctx, const AVFrame *frame)
{
    ctx->integral_aggregator->note_window();

    const AVDictionary *metadata = frame->metadata;

    // ebur128 runs cumulatively and republishes on every 100 ms block, so the
    // last finite snapshot before EOF is the whole-stream figure. A stream
    // shorter than the 400 ms gating block never yields a finite integrated
    // loudness, which is exactly how it should read: unmeasured.
    double integrated_lufs = std::numeric_limits<double>::quiet_NaN();
    double true_peak       = std::numeric_limits<double>::quiet_NaN();
    read_metadata_double(metadata, "lavfi.r128.I", &integrated_lufs);
    read_metadata_double(metadata, "lavfi.r128.true_peak", &true_peak);
    ctx->integral_aggregator->set_loudness_snapshot(integrated_lufs, true_peak);

    double peak_level  = std::numeric_limits<double>::quiet_NaN();
    double flat_factor = std::numeric_limits<double>::quiet_NaN();
    read_metadata_double(metadata, "lavfi.astats.Overall.Peak_level", &peak_level);
    read_metadata_double(metadata, "lavfi.astats.Overall.Flat_factor", &flat_factor);
    ctx->integral_aggregator->set_level_snapshot(peak_level, flat_factor);

    // The chain pins the format to dbl at its head and ebur128, the only filter
    // after it, works in dbl too — so the sink delivers packed interleaved
    // float64.
    if (frame->format != AV_SAMPLE_FMT_DBL) {
        if (!ctx->warned_non_float) {
            ctx->warned_non_float = true;
            ALOGW("harvest_integral_frame: sink delivered format %d, expected DBL — "
                  "peak and clipping counts skipped", frame->format);
        }
        return;
    }
    if (frame->nb_samples > 0 && frame->data[0] != nullptr) {
        ctx->integral_aggregator->add_samples(
                reinterpret_cast<const double *>(frame->data[0]),
                static_cast<size_t>(frame->nb_samples));
    }
}

// Routes one output frame to the aggregate the session was opened for.
static void harvest_frame(AnalysisCtx *ctx, const AVFrame *frame)
{
    if (ctx->mode == AnalysisMode::Integral) {
        harvest_integral_frame(ctx, frame);
    } else {
        harvest_stationary_frame(ctx, frame);
    }
}

// Drains everything the sink currently holds. Returns false only on a real
// filter error (EAGAIN and EOF are normal loop exits).
static bool drain_sink(AnalysisCtx *ctx)
{
    while (true) {
        AVFrame *out = ctx->out_frame;
        av_frame_unref(out);

        const int ret = av_buffersink_get_frame(ctx->sink_ctx, out);
        if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
            return true;
        }
        if (ret < 0) {
            char errbuf[128]; av_strerror(ret, errbuf, sizeof(errbuf));
            ALOGE("drain_sink: av_buffersink_get_frame failed: %s", errbuf);
            av_frame_unref(out);
            return false;
        }

        harvest_frame(ctx, out);
        av_frame_unref(out);
    }
}

// ─── Session operations shared by both measurement modes ─────────────────────

// Opens a session for `mode`. Returns the handle, or 0 with the reason recorded
// in the thread-local init error — the failure contract both Kotlin bridges
// document.
static jlong analysis_open(
        AnalysisMode mode, jint sampleRateHz, jint channelCount, jint inputEncoding)
{
    clear_last_init_error();

    if (sampleRateHz <= 0 || channelCount <= 0) {
        set_last_init_error("invalid analysis params");
        ALOGE("nativeOpen: invalid params (rate=%d ch=%d)",
              static_cast<int>(sampleRateHz), static_cast<int>(channelCount));
        return 0L;
    }

    const AVSampleFormat input_fmt = encoding_to_avsamplefmt(static_cast<int>(inputEncoding));
    if (input_fmt == AV_SAMPLE_FMT_NONE) {
        set_last_init_error("unsupported PCM encoding");
        ALOGE("nativeOpen: unsupported encoding %d", static_cast<int>(inputEncoding));
        return 0L;
    }

    std::unique_ptr<AnalysisCtx> ctx(new (std::nothrow) AnalysisCtx());
    if (!ctx) {
        set_last_init_error("out of memory allocating AnalysisCtx");
        ALOGE("nativeOpen: out of memory");
        return 0L;
    }

    ctx->mode           = mode;
    ctx->channels       = static_cast<int>(channelCount);
    ctx->sample_rate    = static_cast<int>(sampleRateHz);
    ctx->input_encoding = static_cast<int>(inputEncoding);
    ctx->input_av_fmt   = input_fmt;

    if (mode == AnalysisMode::Integral) {
        ctx->integral_aggregator.reset(
                new (std::nothrow) AudioIntegralAggregator(ctx->channels));
    } else {
        ctx->aggregator.reset(new (std::nothrow) AudioAnalysisAggregator(ctx->channels));
    }
    if (!ctx->aggregator && !ctx->integral_aggregator) {
        set_last_init_error("out of memory allocating aggregator");
        ALOGE("nativeOpen: out of memory (aggregator)");
        return 0L;
    }

    char chain[512];
    const bool chain_built = (mode == AnalysisMode::Integral)
            ? build_integral_chain(chain, sizeof(chain))
            : build_measurement_chain(chain, sizeof(chain));
    if (!chain_built) {
        set_last_init_error("measurement chain string construction failed");
        ALOGE("nativeOpen: measurement chain construction failed");
        return 0L;
    }

    if (!build_filter_graph(ctx.get(), chain)) {
        ALOGE("nativeOpen: measurement graph initialisation failed");
        return 0L;
    }

    ALOGD("Analysis session open: mode=%s rate=%dHz ch=%d enc=%d chain='%s'",
          (mode == AnalysisMode::Integral) ? "integral" : "stationary",
          ctx->sample_rate, ctx->channels, ctx->input_encoding, chain);
    return reinterpret_cast<jlong>(ctx.release());
}

// Hands the thread-local init error to Kotlin and clears it.
static jstring analysis_consume_last_init_error(JNIEnv *env)
{
    const std::string message = g_last_init_error;
    g_last_init_error.clear();
    return env->NewStringUTF(message.c_str());
}

// Pushes one window through the graph and drains whatever it produced.
static jint analysis_feed(
        JNIEnv *env, jlong handle, jobject inputBuffer, jint inputFrames)
{
    if (handle == 0L) return ANALYSIS_ERR_INVALID_HANDLE;
    auto *ctx = reinterpret_cast<AnalysisCtx *>(handle);

    if (ctx->finalised) {
        ALOGW("nativeFeed: session already finalised by nativeReadFeatures");
        return ANALYSIS_ERR_INVALID_ARGS;
    }
    if (inputFrames <= 0 || ctx->channels <= 0) {
        ALOGE("nativeFeed: invalid frame/channel count frames=%d ch=%d",
              static_cast<int>(inputFrames), ctx->channels);
        return ANALYSIS_ERR_INVALID_ARGS;
    }

    const auto *in_ptr = static_cast<const uint8_t *>(env->GetDirectBufferAddress(inputBuffer));
    const jlong capacity = env->GetDirectBufferCapacity(inputBuffer);
    const int bytes_per_sample = av_get_bytes_per_sample(ctx->input_av_fmt);
    const int64_t required_bytes =
            static_cast<int64_t>(inputFrames) * ctx->channels * bytes_per_sample;
    if (!in_ptr || bytes_per_sample <= 0 || capacity < required_bytes) {
        ALOGE("nativeFeed: direct ByteBuffer required and must hold %lld bytes",
              static_cast<long long>(required_bytes));
        return ANALYSIS_ERR_INVALID_ARGS;
    }

    AVFrame *in = ctx->in_frame;
    av_frame_unref(in);
    in->nb_samples  = static_cast<int>(inputFrames);
    in->sample_rate = ctx->sample_rate;
    in->format      = ctx->input_av_fmt;
    av_channel_layout_default(&in->ch_layout, ctx->channels);

    // Interleaved formats (S16/S32/FLT) keep everything in data[0].
    in->data[0]       = const_cast<uint8_t *>(in_ptr);
    in->linesize[0]   = static_cast<int>(required_bytes);
    in->extended_data = in->data;

    // KEEP_REF makes libavfilter copy out of the caller's buffer, so the
    // ByteBuffer stays owned by Kotlin and may be refilled immediately.
    const int ret = av_buffersrc_add_frame_flags(
            ctx->src_ctx, in,
            AV_BUFFERSRC_FLAG_KEEP_REF | AV_BUFFERSRC_FLAG_NO_CHECK_FORMAT);
    if (ret < 0) {
        char errbuf[128]; av_strerror(ret, errbuf, sizeof(errbuf));
        ALOGE("nativeFeed: av_buffersrc_add_frame_flags failed: %s", errbuf);
        return ANALYSIS_ERR_GRAPH_FAILED;
    }

    if (!drain_sink(ctx)) {
        return ANALYSIS_ERR_GRAPH_FAILED;
    }
    return inputFrames;
}

// Pushes EOF through the graph exactly once so the stats filters flush their
// last partial window. Feeding after this point is rejected, which is why the
// documented order is open → feed → read → close.
static bool analysis_finalise(AnalysisCtx *ctx)
{
    if (ctx->finalised) {
        return true;
    }
    ctx->finalised = true;
    const int ret = av_buffersrc_add_frame_flags(ctx->src_ctx, nullptr, 0);
    if (ret < 0 && ret != AVERROR_EOF) {
        char errbuf[128]; av_strerror(ret, errbuf, sizeof(errbuf));
        ALOGW("nativeReadFeatures: EOF signal failed: %s", errbuf);
    }
    return drain_sink(ctx);
}

// Validates the destination array against the slot count the caller's mode
// produces.
static bool analysis_output_array_fits(JNIEnv *env, jdoubleArray out, size_t required)
{
    if (out == nullptr) {
        return false;
    }
    const jsize capacity = env->GetArrayLength(out);
    if (static_cast<size_t>(capacity) < required) {
        ALOGE("nativeReadFeatures: output array holds %d of %zu required slots",
              static_cast<int>(capacity), required);
        return false;
    }
    return true;
}

// ─── JNI entry points: AudioAnalysisBridge (Class S) ─────────────────────────

extern "C" JNIEXPORT jlong JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_analysis_AudioAnalysisBridge_nativeOpen(
        JNIEnv *, jclass,
        jint sampleRateHz, jint channelCount, jint inputEncoding)
{
    return analysis_open(AnalysisMode::Stationary, sampleRateHz, channelCount, inputEncoding);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_analysis_AudioAnalysisBridge_nativeConsumeLastInitError(
        JNIEnv *env, jclass)
{
    return analysis_consume_last_init_error(env);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_analysis_AudioAnalysisBridge_nativeFeed(
        JNIEnv *env, jclass,
        jlong handle, jobject inputBuffer, jint inputFrames)
{
    return analysis_feed(env, handle, inputBuffer, inputFrames);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_analysis_AudioAnalysisBridge_nativeReadFeatures(
        JNIEnv *env, jclass,
        jlong handle, jdoubleArray outValues)
{
    if (handle == 0L) return ANALYSIS_ERR_INVALID_HANDLE;
    auto *ctx = reinterpret_cast<AnalysisCtx *>(handle);

    if (ctx->mode != AnalysisMode::Stationary || !ctx->aggregator) {
        ALOGE("nativeReadFeatures: handle was not opened for stationary analysis");
        return ANALYSIS_ERR_INVALID_ARGS;
    }
    if (!analysis_output_array_fits(env, outValues, kAudioAnalysisFeatureCount)) {
        return ANALYSIS_ERR_INVALID_ARGS;
    }
    if (!analysis_finalise(ctx)) {
        return ANALYSIS_ERR_GRAPH_FAILED;
    }

    double features[kAudioAnalysisFeatureCount];
    const size_t written = ctx->aggregator->write_features(features, kAudioAnalysisFeatureCount);
    if (written != kAudioAnalysisFeatureCount) {
        return ANALYSIS_ERR_GRAPH_FAILED;
    }

    env->SetDoubleArrayRegion(outValues, 0, static_cast<jsize>(written), features);
    return static_cast<jint>(written);
}

extern "C" JNIEXPORT void JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_analysis_AudioAnalysisBridge_nativeClose(
        JNIEnv *, jclass, jlong handle)
{
    if (handle == 0L) return;
    auto *ctx = reinterpret_cast<AnalysisCtx *>(handle);
    ALOGD("nativeClose: releasing analysis session (windows=%llu)",
          static_cast<unsigned long long>(ctx->aggregator ? ctx->aggregator->window_count() : 0));
    teardown_filter_graph(ctx);
    delete ctx;
}

// ─── JNI entry points: AudioIntegralAnalysisBridge (Class I) ─────────────────

extern "C" JNIEXPORT jlong JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_analysis_AudioIntegralAnalysisBridge_nativeOpen(
        JNIEnv *, jclass,
        jint sampleRateHz, jint channelCount, jint inputEncoding)
{
    return analysis_open(AnalysisMode::Integral, sampleRateHz, channelCount, inputEncoding);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_analysis_AudioIntegralAnalysisBridge_nativeConsumeLastInitError(
        JNIEnv *env, jclass)
{
    return analysis_consume_last_init_error(env);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_analysis_AudioIntegralAnalysisBridge_nativeFeed(
        JNIEnv *env, jclass,
        jlong handle, jobject inputBuffer, jint inputFrames)
{
    return analysis_feed(env, handle, inputBuffer, inputFrames);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_analysis_AudioIntegralAnalysisBridge_nativeReadFeatures(
        JNIEnv *env, jclass,
        jlong handle, jdoubleArray outValues)
{
    if (handle == 0L) return ANALYSIS_ERR_INVALID_HANDLE;
    auto *ctx = reinterpret_cast<AnalysisCtx *>(handle);

    if (ctx->mode != AnalysisMode::Integral || !ctx->integral_aggregator) {
        ALOGE("nativeReadFeatures: handle was not opened for integral analysis");
        return ANALYSIS_ERR_INVALID_ARGS;
    }
    if (!analysis_output_array_fits(env, outValues, kAudioIntegralFeatureCount)) {
        return ANALYSIS_ERR_INVALID_ARGS;
    }
    if (!analysis_finalise(ctx)) {
        return ANALYSIS_ERR_GRAPH_FAILED;
    }

    // Closes the flat-top runs that were still open when the last frame
    // arrived; idempotent, so a second read reports the same aggregate.
    ctx->integral_aggregator->finish();

    double features[kAudioIntegralFeatureCount];
    const size_t written =
            ctx->integral_aggregator->write_features(features, kAudioIntegralFeatureCount);
    if (written != kAudioIntegralFeatureCount) {
        return ANALYSIS_ERR_GRAPH_FAILED;
    }

    // The counted peak and the filter's own are logged side by side: they
    // measure the same thing by two independent routes, so a disagreement is a
    // bug in one of them and must not stay invisible.
    ALOGD("Integral aggregate: counted peak=%.3f dBFS astats peak=%.3f dBFS "
          "flat_factor=%.3f LUFS=%.2f true_peak=%.3f dBFS frames=%.0f",
          features[kIntegralSamplePeakDbfs],
          ctx->integral_aggregator->astats_peak_level_dbfs(),
          ctx->integral_aggregator->astats_flat_factor(),
          features[kIntegralIntegratedLufs],
          features[kIntegralTruePeakDbfs],
          features[kIntegralFrameCount]);

    env->SetDoubleArrayRegion(outValues, 0, static_cast<jsize>(written), features);
    return static_cast<jint>(written);
}

extern "C" JNIEXPORT void JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_analysis_AudioIntegralAnalysisBridge_nativeClose(
        JNIEnv *, jclass, jlong handle)
{
    if (handle == 0L) return;
    auto *ctx = reinterpret_cast<AnalysisCtx *>(handle);
    ALOGD("nativeClose: releasing integral session (windows=%llu)",
          static_cast<unsigned long long>(
                  ctx->integral_aggregator ? ctx->integral_aggregator->window_count() : 0));
    teardown_filter_graph(ctx);
    delete ctx;
}
