// ─────────────────────────────────────────────────────────────────────────────
// audio_analysis_aggregator.h
//
// Pure aggregation and parsing logic behind the Class S measurement bridge.
//
// Everything here is deliberately free of JNI, Android and FFmpeg so it can be
// compiled and asserted on the host under ASan+UBSan (see tests/CMakeLists.txt).
// `audio_analysis_bridge.cpp` owns the lavfi graph and feeds this class two
// kinds of input per output frame:
//
//   1. the numbers parsed out of the frame metadata dictionary
//      (lavfi.aspectralstats.<ch>.* and lavfi.astats.Overall.*), and
//   2. the interleaved float32 samples of that same frame, from which the
//      channel / mid / side energies and the inter-channel correlation are
//      derived directly.
//
// (2) exists because no filter in the shipped FFmpeg 7.1.4 build exposes
// mid/side energy or an inter-channel correlation coefficient as metadata:
// `astats` measures each channel in isolation, and `aphasemeter` — the only
// filter that reports a correlation at all — publishes a sign-correlation meter
// value and opens a second, video output pad. The three sums accumulated here
// are exact, cost one pass over samples the graph has already produced, and are
// testable without a decoder.
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
// constants in AudioAnalysisBridge.kt; the Kotlin side rejects any array whose
// length is not kAudioAnalysisFeatureCount.
//
// Slots that were never measured (a metadata key the graph did not emit, a
// window count of zero, silence with no defined correlation) are written as
// NaN, which the Kotlin layer maps to `null`.
enum AudioAnalysisFeatureIndex : size_t {
    kFeatureSpectralRolloffHz       = 0,   // Hz — highest frequency carrying energy
    kFeatureSpectralCentroidHz      = 1,   // Hz — spectral centre of mass
    kFeatureSpectralSlope           = 2,   // spectral tilt (unitless, < 0 = dark)
    kFeatureNoiseFloorDbfs          = 3,   // dBFS — astats Overall.Noise_floor
    kFeatureDcOffset                = 4,   // normalised [-1, 1] DC bias
    kFeatureLeftRmsDbfs             = 5,   // dBFS — first channel energy
    kFeatureRightRmsDbfs            = 6,   // dBFS — second channel energy
    kFeatureMidRmsDbfs              = 7,   // dBFS — (L+R)/2 energy
    kFeatureSideRmsDbfs             = 8,   // dBFS — (L-R)/2 energy
    kFeatureInterChannelCorrelation = 9,   // [-1, 1] — +1 mono, 0 uncorrelated
    kFeatureWindowCount             = 10,  // analysed output frames
    kFeatureFrameCount              = 11,  // analysed PCM frames
};

/** Number of slots in the feature vector exchanged across JNI. */
inline constexpr size_t kAudioAnalysisFeatureCount = 12;

/**
 * Floor reported instead of -inf when a measured signal is digital silence.
 *
 * Digital silence is a legitimate measurement, not a missing one, so it must
 * not collapse to NaN (which the Kotlin layer reads as "not measured"). -200 dB
 * is far below the resolution of any format this player decodes.
 */
inline constexpr double kAudioAnalysisSilenceDbfs = -200.0;

/**
 * Parses one lavfi metadata value into a finite double.
 *
 * astats prints silent levels as "-inf" and aspectralstats can emit "nan" for a
 * window with no energy; both are rejected here so a single degenerate window
 * cannot poison an average.
 *
 * @param text  NUL-terminated metadata value, may be null.
 * @param out   Receives the parsed value; untouched unless true is returned.
 * @return true when the whole string parsed to a finite number.
 */
[[nodiscard]] bool parse_measured_double(const char *text, double *out) noexcept;

/**
 * Arithmetic mean of the finite samples handed to it, ignoring the rest.
 *
 * Non-finite values are dropped rather than propagated: aspectralstats reports
 * per-window, per-channel numbers, and one silent window in an otherwise normal
 * track must not turn the whole track's centroid into NaN.
 */
class RunningMean {
public:
    /** Adds one observation; non-finite values are ignored. */
    void add(double value) noexcept;

    /** True once at least one finite observation has been added. */
    [[nodiscard]] bool has_value() const noexcept { return count_ > 0; }

    /** Mean of the finite observations, or NaN when there were none. */
    [[nodiscard]] double value() const noexcept;

    /** Number of finite observations accumulated. */
    [[nodiscard]] uint64_t count() const noexcept { return count_; }

private:
    double   sum_{0.0};
    uint64_t count_{0};
};

/**
 * Stereo energy relationships derived from raw interleaved float32 samples.
 *
 * Accumulates only three sums — sum(L*L), sum(R*R) and sum(L*R) — from which the
 * mid and side energies follow exactly:
 *
 *   sum(M*M) = (sum(L*L) + sum(R*R) + 2*sum(L*R)) / 4     with M = (L+R)/2
 *   sum(S*S) = (sum(L*L) + sum(R*R) - 2*sum(L*R)) / 4     with S = (L-R)/2
 *
 * Mono sources report side energy at the silence floor and a correlation of
 * exactly 1.0. Sources with more than two channels are measured on the first
 * two (front left / front right); the remaining channels are not part of the
 * stereo-width question this feature answers.
 */
class StereoEnergyAccumulator {
public:
    /**
     * @param channel_count Interleaved channel count of the frames that will be
     *   supplied; values below 1 disable accumulation.
     */
    explicit StereoEnergyAccumulator(int channel_count) noexcept;

    /**
     * Accumulates one block of interleaved float32 frames.
     *
     * A frame containing any non-finite sample is skipped whole so a decoder
     * glitch cannot turn the aggregate into NaN.
     */
    void add_interleaved(const float *samples, size_t frame_count) noexcept;

    /** PCM frames accumulated so far. */
    [[nodiscard]] uint64_t frame_count() const noexcept { return frames_; }

    /** First-channel RMS in dBFS, or NaN when nothing was accumulated. */
    [[nodiscard]] double left_rms_dbfs() const noexcept;

    /** Second-channel RMS in dBFS; equals left_rms_dbfs() for mono sources. */
    [[nodiscard]] double right_rms_dbfs() const noexcept;

    /** (L+R)/2 RMS in dBFS, or NaN when nothing was accumulated. */
    [[nodiscard]] double mid_rms_dbfs() const noexcept;

    /** (L-R)/2 RMS in dBFS; the silence floor for mono sources. */
    [[nodiscard]] double side_rms_dbfs() const noexcept;

    /**
     * Inter-channel correlation in [-1, 1], NaN when undefined.
     *
     * Undefined means either channel is digitally silent, where the ratio has
     * no meaning; mono sources short-circuit to exactly 1.0.
     */
    [[nodiscard]] double correlation() const noexcept;

private:
    int      channels_;
    uint64_t frames_{0};
    double   sum_ll_{0.0};
    double   sum_rr_{0.0};
    double   sum_lr_{0.0};
};

/**
 * Whole-track Class S aggregate assembled from every analysed window.
 *
 * Spectral values are averaged across windows and channels. The astats levels
 * are cumulative by construction — the filter runs with `reset=0`, so each
 * output frame carries the running aggregate — and therefore the most recent
 * finite snapshot is kept rather than averaged.
 */
class AudioAnalysisAggregator {
public:
    /** @param channel_count Interleaved channel count of the analysed source. */
    explicit AudioAnalysisAggregator(int channel_count) noexcept;

    /** Records one aspectralstats observation for a single channel of a window. */
    void add_spectral_channel(double centroid_hz, double rolloff_hz, double slope) noexcept;

    /** Records the latest cumulative astats levels; non-finite values are ignored. */
    void set_level_snapshot(double noise_floor_dbfs, double dc_offset) noexcept;

    /** Accumulates the interleaved float32 samples of one analysed window. */
    void add_samples(const float *interleaved, size_t frame_count) noexcept;

    /** Marks one analysed window (one graph output frame). */
    void note_window() noexcept { windows_++; }

    /** Windows analysed so far. */
    [[nodiscard]] uint64_t window_count() const noexcept { return windows_; }

    /**
     * Writes the feature vector, NaN for every slot that was never measured.
     *
     * @param out      Destination of at least kAudioAnalysisFeatureCount doubles.
     * @param capacity Length of `out`.
     * @return Number of slots written, or 0 when `out` is null or too small.
     */
    size_t write_features(double *out, size_t capacity) const noexcept;

private:
    RunningMean             centroid_;
    RunningMean             rolloff_;
    RunningMean             slope_;
    StereoEnergyAccumulator energy_;
    double                  noise_floor_dbfs_;
    double                  dc_offset_;
    uint64_t                windows_{0};
};
