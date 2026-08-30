// ─────────────────────────────────────────────────────────────────────────────
// audio_analysis_aggregator.cpp
//
// Implementation of the pure Class S aggregation logic. See the header for the
// contract and for why the stereo relationships are computed here rather than
// read out of filter metadata.
// ─────────────────────────────────────────────────────────────────────────────

#include "audio_analysis_aggregator.h"

#include <cerrno>
#include <cmath>
#include <cstdlib>
#include <limits>

namespace {

constexpr double kNaN = std::numeric_limits<double>::quiet_NaN();

// Converts a mean square to dBFS, folding true digital silence onto the
// documented floor instead of -inf.
double mean_square_to_dbfs(double mean_square) noexcept
{
    if (!std::isfinite(mean_square) || mean_square <= 0.0) {
        return kAudioAnalysisSilenceDbfs;
    }
    const double dbfs = 10.0 * std::log10(mean_square);
    return (dbfs < kAudioAnalysisSilenceDbfs) ? kAudioAnalysisSilenceDbfs : dbfs;
}

} // namespace

// ─── parse_measured_double ───────────────────────────────────────────────────

bool parse_measured_double(const char *text, double *out) noexcept
{
    if (text == nullptr || out == nullptr || text[0] == '\0') {
        return false;
    }

    errno = 0;
    char *end = nullptr;
    const double parsed = std::strtod(text, &end);

    if (end == text) {
        return false;                       // nothing numeric at all
    }
    // strtod accepts "inf" / "nan"; both are meaningless as a measurement.
    if (!std::isfinite(parsed)) {
        return false;
    }
    if (errno == ERANGE) {
        return false;
    }
    // Trailing garbage means the key did not hold a bare number.
    while (*end == ' ' || *end == '\t' || *end == '\n' || *end == '\r') {
        end++;
    }
    if (*end != '\0') {
        return false;
    }

    *out = parsed;
    return true;
}

// ─── RunningMean ─────────────────────────────────────────────────────────────

void RunningMean::add(double value) noexcept
{
    if (!std::isfinite(value)) {
        return;
    }
    sum_ += value;
    count_++;
}

double RunningMean::value() const noexcept
{
    if (count_ == 0) {
        return kNaN;
    }
    return sum_ / static_cast<double>(count_);
}

// ─── StereoEnergyAccumulator ─────────────────────────────────────────────────

StereoEnergyAccumulator::StereoEnergyAccumulator(int channel_count) noexcept
    : channels_(channel_count)
{
}

void StereoEnergyAccumulator::add_interleaved(const float *samples, size_t frame_count) noexcept
{
    if (samples == nullptr || frame_count == 0 || channels_ < 1) {
        return;
    }

    // Mono: left and right are the same signal, so the correlation is 1 and the
    // side channel is silent. Accumulating L into both keeps every derived
    // formula in this class valid without a special case downstream.
    const size_t stride      = static_cast<size_t>(channels_);
    const size_t right_index = (channels_ >= 2) ? 1U : 0U;

    for (size_t frame = 0; frame < frame_count; frame++) {
        const double left  = static_cast<double>(samples[frame * stride]);
        const double right = static_cast<double>(samples[frame * stride + right_index]);
        if (!std::isfinite(left) || !std::isfinite(right)) {
            continue;
        }
        sum_ll_ += left * left;
        sum_rr_ += right * right;
        sum_lr_ += left * right;
        frames_++;
    }
}

double StereoEnergyAccumulator::left_rms_dbfs() const noexcept
{
    if (frames_ == 0) {
        return kNaN;
    }
    return mean_square_to_dbfs(sum_ll_ / static_cast<double>(frames_));
}

double StereoEnergyAccumulator::right_rms_dbfs() const noexcept
{
    if (frames_ == 0) {
        return kNaN;
    }
    return mean_square_to_dbfs(sum_rr_ / static_cast<double>(frames_));
}

double StereoEnergyAccumulator::mid_rms_dbfs() const noexcept
{
    if (frames_ == 0) {
        return kNaN;
    }
    const double sum_mm = (sum_ll_ + sum_rr_ + 2.0 * sum_lr_) / 4.0;
    return mean_square_to_dbfs(sum_mm / static_cast<double>(frames_));
}

double StereoEnergyAccumulator::side_rms_dbfs() const noexcept
{
    if (frames_ == 0) {
        return kNaN;
    }
    // Floating-point cancellation can push a mathematically zero side energy a
    // hair below zero; mean_square_to_dbfs folds that onto the silence floor.
    const double sum_ss = (sum_ll_ + sum_rr_ - 2.0 * sum_lr_) / 4.0;
    return mean_square_to_dbfs(sum_ss / static_cast<double>(frames_));
}

double StereoEnergyAccumulator::correlation() const noexcept
{
    if (frames_ == 0) {
        return kNaN;
    }
    if (channels_ < 2) {
        return 1.0;                          // one signal is perfectly correlated with itself
    }
    const double denominator = std::sqrt(sum_ll_ * sum_rr_);
    if (!std::isfinite(denominator) || denominator <= 0.0) {
        return kNaN;                         // a silent channel has no defined correlation
    }
    const double correlation = sum_lr_ / denominator;
    if (!std::isfinite(correlation)) {
        return kNaN;
    }
    // Clamp away the rounding overshoot an identical pair of channels produces.
    if (correlation > 1.0) return 1.0;
    if (correlation < -1.0) return -1.0;
    return correlation;
}

// ─── AudioAnalysisAggregator ─────────────────────────────────────────────────

AudioAnalysisAggregator::AudioAnalysisAggregator(int channel_count) noexcept
    : energy_(channel_count),
      noise_floor_dbfs_(kNaN),
      dc_offset_(kNaN)
{
}

void AudioAnalysisAggregator::add_spectral_channel(
        double centroid_hz, double rolloff_hz, double slope) noexcept
{
    centroid_.add(centroid_hz);
    rolloff_.add(rolloff_hz);
    slope_.add(slope);
}

void AudioAnalysisAggregator::set_level_snapshot(
        double noise_floor_dbfs, double dc_offset) noexcept
{
    if (std::isfinite(noise_floor_dbfs)) {
        noise_floor_dbfs_ = noise_floor_dbfs;
    }
    if (std::isfinite(dc_offset)) {
        dc_offset_ = dc_offset;
    }
}

void AudioAnalysisAggregator::add_samples(const float *interleaved, size_t frame_count) noexcept
{
    energy_.add_interleaved(interleaved, frame_count);
}

size_t AudioAnalysisAggregator::write_features(double *out, size_t capacity) const noexcept
{
    if (out == nullptr || capacity < kAudioAnalysisFeatureCount) {
        return 0;
    }

    out[kFeatureSpectralRolloffHz]       = rolloff_.value();
    out[kFeatureSpectralCentroidHz]      = centroid_.value();
    out[kFeatureSpectralSlope]           = slope_.value();
    out[kFeatureNoiseFloorDbfs]          = noise_floor_dbfs_;
    out[kFeatureDcOffset]                = dc_offset_;
    out[kFeatureLeftRmsDbfs]             = energy_.left_rms_dbfs();
    out[kFeatureRightRmsDbfs]            = energy_.right_rms_dbfs();
    out[kFeatureMidRmsDbfs]              = energy_.mid_rms_dbfs();
    out[kFeatureSideRmsDbfs]             = energy_.side_rms_dbfs();
    out[kFeatureInterChannelCorrelation] = energy_.correlation();
    out[kFeatureWindowCount]             = static_cast<double>(windows_);
    out[kFeatureFrameCount]              = static_cast<double>(energy_.frame_count());

    return kAudioAnalysisFeatureCount;
}
