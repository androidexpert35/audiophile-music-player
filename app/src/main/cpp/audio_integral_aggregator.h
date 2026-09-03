// ─────────────────────────────────────────────────────────────────────────────
// audio_integral_aggregator.h
//
// Pure aggregation logic behind the Class I (integral) measurement mode of the
// analysis bridge.
//
// Like audio_analysis_aggregator.h this is deliberately free of JNI, Android
// and FFmpeg so it can be compiled and asserted on the host under ASan+UBSan
// (see tests/CMakeLists.txt). `audio_analysis_bridge.cpp` owns the lavfi graph
// and feeds this class two kinds of input per output frame:
//
//   1. the numbers parsed out of the frame metadata dictionary
//      (lavfi.r128.* from ebur128, lavfi.astats.Overall.* from astats), and
//   2. the interleaved float64 samples of that same frame, from which the
//      sample peak, the clipping ratio and the flat-top run-length statistics
//      are derived directly.
//
// (2) exists because none of those three can be read safely out of metadata in
// the shipped FFmpeg 7.1.4 build. `astats` reports `Overall.Abs_Peak_count` as
// the number of samples sitting at the *observed* maximum, not at full scale —
// on a quiet track that is a count of its own loudest samples and would read as
// a catastrophic clipping ratio — and its `Flat_factor` is a single scalar, not
// a run-length distribution. Counting them here is exact, costs one pass over
// samples the graph has already produced, and is testable without a decoder.
//
// ### Why an integral pass is a different pass
//
// These measures are properties of the *whole* stream. A peak seen in three
// sampled windows is not the peak of the track, and an underestimated peak is
// worse than no peak because a gain stage will trust it. Nothing in this file
// may therefore be fed from a sampled subset of a track.
//
// Measurement only: nothing in this file touches the playback data path.
// ─────────────────────────────────────────────────────────────────────────────

#pragma once

#include <cstddef>
#include <cstdint>

// ─── Feature vector layout ───────────────────────────────────────────────────
//
// The bridge hands the aggregate back to Kotlin as a flat array of doubles.
// The order below is the wire contract and MUST stay in sync with the index
// constants in AudioIntegralAnalysisBridge.kt; the Kotlin side rejects any
// array whose length is not kAudioIntegralFeatureCount.
//
// Slots that were never measured (a metadata key the graph did not emit, a
// stream too short for ebur128's 400 ms gating block, a track with no flat-top
// run at all) are written as NaN, which the Kotlin layer maps to `null`.
enum AudioIntegralFeatureIndex : size_t {
    kIntegralSamplePeakDbfs      = 0,   // dBFS — exact absolute sample peak
    kIntegralTruePeakDbfs        = 1,   // dBFS — ebur128 inter-sample peak
    kIntegralIntegratedLufs      = 2,   // LUFS — EBU R128 integrated loudness
    kIntegralPlrDb               = 3,   // dB   — sample peak minus loudness
    kIntegralClippingRatio       = 4,   // [0, 1] fraction of samples at full scale
    kIntegralFlatRunCount        = 5,   // count of flat-top runs found
    kIntegralFlatRunLongest      = 6,   // samples in the longest flat-top run
    kIntegralFlatRunMean         = 7,   // mean length of a flat-top run, samples
    kIntegralFlatRunSampleRatio  = 8,   // [0, 1] fraction of samples inside runs
    kIntegralFrameCount          = 9,   // PCM frames the aggregate covers
};

/** Number of slots in the integral feature vector exchanged across JNI. */
inline constexpr size_t kAudioIntegralFeatureCount = 10;

/**
 * Floor reported instead of -inf when the measured stream is digital silence.
 *
 * Matches kAudioAnalysisSilenceDbfs in audio_analysis_aggregator.h: digital
 * silence is a legitimate measurement, not a missing one, so it must not
 * collapse to NaN (which the Kotlin layer reads as "not measured").
 */
inline constexpr double kAudioIntegralSilenceDbfs = -200.0;

/**
 * Magnitude at or above which a sample counts as sitting at full scale.
 *
 * Not exactly 1.0 on purpose. FFmpeg normalises integer PCM by the negative
 * full-scale value, so the largest positive sample a 16-bit source can produce
 * is 32767/32768 = 0.999969 and a 24-bit source 0.99999988. A threshold of
 * exactly 1.0 would therefore report zero clipped samples for a 16-bit master
 * that is clipped solid, which is the opposite of the truth. 0.9999 is about
 * -0.00087 dBFS: below every format's positive maximum, above anything an
 * uncompressed master reaches by accident.
 */
inline constexpr double kAudioIntegralFullScale = 0.9999;

/**
 * Shortest run of consecutive full-scale samples counted as a flat top.
 *
 * Two adjacent samples at full scale happen in ordinary loud music; three or
 * more is a plateau, which is what clipping (in the encoder, the master or the
 * A/D) actually leaves behind. This is the length the run-length statistics
 * are gathered over.
 */
inline constexpr uint32_t kAudioIntegralMinFlatRun = 3;

/**
 * Whole-stream sample statistics accumulated straight from decoded audio.
 *
 * Every channel is examined independently: a flat top on one channel of a
 * stereo master is a flat top, and averaging the channels first would hide it.
 * Runs are tracked per channel and closed when that channel drops below full
 * scale or when [finish] is called at end of stream.
 */
class IntegralSampleAccumulator {
public:
    /**
     * @param channel_count Interleaved channel count of the frames that will be
     *   supplied; values below 1 disable accumulation.
     */
    explicit IntegralSampleAccumulator(int channel_count) noexcept;

    /**
     * Accumulates one block of interleaved float64 frames.
     *
     * A frame containing any non-finite sample is skipped whole so a decoder
     * glitch cannot turn the aggregate into NaN; a skipped frame also closes
     * any open flat-top run, because the run is no longer known to be
     * contiguous.
     */
    void add_interleaved(const double *samples, size_t frame_count) noexcept;

    /**
     * Closes any run still open at end of stream.
     *
     * Must be called once, after the last [add_interleaved], before any of the
     * flat-top accessors are read. Calling it twice is harmless.
     */
    void finish() noexcept;

    /** PCM frames accumulated so far. */
    [[nodiscard]] uint64_t frame_count() const noexcept { return frames_; }

    /**
     * Absolute sample peak in dBFS, or the silence floor for a silent stream.
     *
     * NaN only when nothing was accumulated at all.
     */
    [[nodiscard]] double sample_peak_dbfs() const noexcept;

    /**
     * Fraction of individual samples at or beyond full scale, in `[0, 1]`.
     *
     * NaN when nothing was accumulated. A non-trivial value means the source
     * arrived clipped; this player never introduces clipping of its own.
     */
    [[nodiscard]] double clipping_ratio() const noexcept;

    /** Number of flat-top runs of at least kAudioIntegralMinFlatRun samples. */
    [[nodiscard]] uint64_t flat_run_count() const noexcept { return flat_runs_; }

    /** Length in samples of the longest flat-top run, or NaN when there was none. */
    [[nodiscard]] double flat_run_longest() const noexcept;

    /** Mean length in samples of a flat-top run, or NaN when there was none. */
    [[nodiscard]] double flat_run_mean() const noexcept;

    /**
     * Fraction of all samples that sit inside a flat-top run, in `[0, 1]`.
     *
     * NaN when nothing was accumulated. This is the measure that separates a
     * master with a handful of clipped transients from one that is squashed
     * flat for minutes at a time.
     */
    [[nodiscard]] double flat_run_sample_ratio() const noexcept;

private:
    /** Ends the run open on `channel`, recording it when it is long enough. */
    void close_run(size_t channel) noexcept;

    /** Ends every open run; used at end of stream and on a non-finite frame. */
    void close_all_runs() noexcept;

    static constexpr size_t kMaxTrackedChannels = 8;

    size_t   channels_;
    uint64_t frames_{0};
    uint64_t samples_{0};
    uint64_t clipped_samples_{0};
    double   peak_{0.0};

    uint32_t open_run_[kMaxTrackedChannels]{};
    uint64_t flat_runs_{0};
    uint64_t flat_run_samples_{0};
    uint32_t flat_run_longest_{0};
};

/**
 * Whole-stream Class I aggregate assembled from the graph output.
 *
 * The loudness values are cumulative by construction — ebur128 publishes the
 * running integrated loudness and peaks on every 100 ms block, and astats runs
 * with `reset=0` — so the most recent finite snapshot is kept rather than
 * averaged, exactly as the Class S aggregator treats the astats levels.
 */
class AudioIntegralAggregator {
public:
    /** @param channel_count Interleaved channel count of the analysed source. */
    explicit AudioIntegralAggregator(int channel_count) noexcept;

    /**
     * Records the latest cumulative ebur128 snapshot.
     *
     * @param integrated_lufs Running integrated loudness, LUFS.
     * @param true_peak_linear Running true peak as a linear amplitude, which is
     *   the unit `lavfi.r128.true_peak` carries (the filter's own end-of-stream
     *   log is what applies 20·log10 to it).
     * Non-finite values are ignored, which is how a stream shorter than the
     * 400 ms gating block — where the integrated loudness is still -inf —
     * correctly reads as unmeasured.
     */
    void set_loudness_snapshot(double integrated_lufs, double true_peak_linear) noexcept;

    /**
     * Records the latest cumulative astats levels, used only for corroboration.
     *
     * The reported sample peak comes from [IntegralSampleAccumulator], which is
     * exact; this snapshot exists so the two can be compared in a log line and
     * a drift between the graph and the sample pass is visible rather than
     * silent. Non-finite values are ignored.
     */
    void set_level_snapshot(double peak_level_dbfs, double flat_factor) noexcept;

    /** Accumulates the interleaved float64 samples of one graph output frame. */
    void add_samples(const double *interleaved, size_t frame_count) noexcept;

    /** Marks one graph output frame. */
    void note_window() noexcept { windows_++; }

    /** Graph output frames seen so far. */
    [[nodiscard]] uint64_t window_count() const noexcept { return windows_; }

    /** Latest astats overall peak level in dBFS, or NaN when never reported. */
    [[nodiscard]] double astats_peak_level_dbfs() const noexcept { return astats_peak_dbfs_; }

    /** Latest astats flat factor, or NaN when never reported. */
    [[nodiscard]] double astats_flat_factor() const noexcept { return astats_flat_factor_; }

    /**
     * Closes the aggregate at end of stream.
     *
     * Must be called once before [write_features]; it closes the flat-top runs
     * that were still open when the last frame arrived.
     */
    void finish() noexcept;

    /**
     * Writes the feature vector, NaN for every slot that was never measured.
     *
     * @param out      Destination of at least kAudioIntegralFeatureCount doubles.
     * @param capacity Length of `out`.
     * @return Number of slots written, or 0 when `out` is null or too small.
     */
    size_t write_features(double *out, size_t capacity) const noexcept;

private:
    IntegralSampleAccumulator samples_;
    double                    integrated_lufs_;
    double                    true_peak_linear_;
    double                    astats_peak_dbfs_;
    double                    astats_flat_factor_;
    uint64_t                  windows_{0};
};
