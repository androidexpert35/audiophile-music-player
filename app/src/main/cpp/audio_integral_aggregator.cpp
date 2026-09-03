// ─────────────────────────────────────────────────────────────────────────────
// audio_integral_aggregator.cpp
//
// Implementation of the pure Class I aggregation logic. See the header for the
// contract, for why the peak, clipping and flat-top statistics are counted from
// samples rather than read out of filter metadata, and for the full-scale
// threshold this file compares against.
// ─────────────────────────────────────────────────────────────────────────────

#include "audio_integral_aggregator.h"

#include <cmath>
#include <limits>

namespace {

constexpr double kNaN = std::numeric_limits<double>::quiet_NaN();

// Converts a linear amplitude to dBFS, folding true digital silence onto the
// documented floor instead of -inf.
double amplitude_to_dbfs(double amplitude) noexcept
{
    if (!std::isfinite(amplitude) || amplitude <= 0.0) {
        return kAudioIntegralSilenceDbfs;
    }
    const double dbfs = 20.0 * std::log10(amplitude);
    return (dbfs < kAudioIntegralSilenceDbfs) ? kAudioIntegralSilenceDbfs : dbfs;
}

} // namespace

// ─── IntegralSampleAccumulator ───────────────────────────────────────────────

IntegralSampleAccumulator::IntegralSampleAccumulator(int channel_count) noexcept
    : channels_(0)
{
    if (channel_count > 0) {
        channels_ = static_cast<size_t>(channel_count);
    }
    // Runs are tracked for the first kMaxTrackedChannels channels. Beyond that
    // the samples still count towards the peak and the clipping ratio; only the
    // run bookkeeping stops, which no source this player decodes ever reaches.
}

void IntegralSampleAccumulator::close_run(size_t channel) noexcept
{
    if (channel >= kMaxTrackedChannels) {
        return;
    }
    const uint32_t length = open_run_[channel];
    open_run_[channel] = 0;
    if (length < kAudioIntegralMinFlatRun) {
        return;
    }
    flat_runs_++;
    flat_run_samples_ += length;
    if (length > flat_run_longest_) {
        flat_run_longest_ = length;
    }
}

void IntegralSampleAccumulator::close_all_runs() noexcept
{
    for (size_t channel = 0; channel < kMaxTrackedChannels; channel++) {
        close_run(channel);
    }
}

void IntegralSampleAccumulator::add_interleaved(const double *samples, size_t frame_count) noexcept
{
    if (samples == nullptr || frame_count == 0 || channels_ < 1) {
        return;
    }

    for (size_t frame = 0; frame < frame_count; frame++) {
        const double *current = samples + frame * channels_;

        // A frame with a non-finite sample is dropped whole: mixing one channel
        // of it into the peak while discarding the other would be worse than
        // ignoring the frame, and the run either side of it is no longer known
        // to be contiguous.
        bool finite = true;
        for (size_t channel = 0; channel < channels_; channel++) {
            if (!std::isfinite(current[channel])) {
                finite = false;
                break;
            }
        }
        if (!finite) {
            close_all_runs();
            continue;
        }

        for (size_t channel = 0; channel < channels_; channel++) {
            const double magnitude = std::fabs(current[channel]);
            if (magnitude > peak_) {
                peak_ = magnitude;
            }
            if (magnitude >= kAudioIntegralFullScale) {
                clipped_samples_++;
                if (channel < kMaxTrackedChannels) {
                    open_run_[channel]++;
                }
            } else if (channel < kMaxTrackedChannels) {
                close_run(channel);
            }
        }

        frames_++;
        samples_ += channels_;
    }
}

void IntegralSampleAccumulator::finish() noexcept
{
    close_all_runs();
}

double IntegralSampleAccumulator::sample_peak_dbfs() const noexcept
{
    if (frames_ == 0) {
        return kNaN;
    }
    return amplitude_to_dbfs(peak_);
}

double IntegralSampleAccumulator::clipping_ratio() const noexcept
{
    if (samples_ == 0) {
        return kNaN;
    }
    return static_cast<double>(clipped_samples_) / static_cast<double>(samples_);
}

double IntegralSampleAccumulator::flat_run_longest() const noexcept
{
    if (flat_runs_ == 0) {
        return kNaN;
    }
    return static_cast<double>(flat_run_longest_);
}

double IntegralSampleAccumulator::flat_run_mean() const noexcept
{
    if (flat_runs_ == 0) {
        return kNaN;
    }
    return static_cast<double>(flat_run_samples_) / static_cast<double>(flat_runs_);
}

double IntegralSampleAccumulator::flat_run_sample_ratio() const noexcept
{
    if (samples_ == 0) {
        return kNaN;
    }
    return static_cast<double>(flat_run_samples_) / static_cast<double>(samples_);
}

// ─── AudioIntegralAggregator ─────────────────────────────────────────────────

AudioIntegralAggregator::AudioIntegralAggregator(int channel_count) noexcept
    : samples_(channel_count),
      integrated_lufs_(kNaN),
      true_peak_linear_(kNaN),
      astats_peak_dbfs_(kNaN),
      astats_flat_factor_(kNaN)
{
}

void AudioIntegralAggregator::set_loudness_snapshot(
        double integrated_lufs, double true_peak_linear) noexcept
{
    if (std::isfinite(integrated_lufs)) {
        integrated_lufs_ = integrated_lufs;
    }
    if (std::isfinite(true_peak_linear)) {
        true_peak_linear_ = true_peak_linear;
    }
}

void AudioIntegralAggregator::set_level_snapshot(
        double peak_level_dbfs, double flat_factor) noexcept
{
    if (std::isfinite(peak_level_dbfs)) {
        astats_peak_dbfs_ = peak_level_dbfs;
    }
    if (std::isfinite(flat_factor)) {
        astats_flat_factor_ = flat_factor;
    }
}

void AudioIntegralAggregator::add_samples(const double *interleaved, size_t frame_count) noexcept
{
    samples_.add_interleaved(interleaved, frame_count);
}

void AudioIntegralAggregator::finish() noexcept
{
    samples_.finish();
}

size_t AudioIntegralAggregator::write_features(double *out, size_t capacity) const noexcept
{
    if (out == nullptr || capacity < kAudioIntegralFeatureCount) {
        return 0;
    }

    const double sample_peak_dbfs = samples_.sample_peak_dbfs();

    out[kIntegralSamplePeakDbfs] = sample_peak_dbfs;
    out[kIntegralTruePeakDbfs] =
            std::isfinite(true_peak_linear_) ? amplitude_to_dbfs(true_peak_linear_) : kNaN;
    out[kIntegralIntegratedLufs] = integrated_lufs_;

    // PLR is the headroom the master was left with: how far its peak sits above
    // its own loudness. It only exists when both halves were measured, and it
    // is derived here rather than in Kotlin so the two never disagree.
    out[kIntegralPlrDb] =
            (std::isfinite(sample_peak_dbfs) && std::isfinite(integrated_lufs_))
                ? sample_peak_dbfs - integrated_lufs_
                : kNaN;

    out[kIntegralClippingRatio]      = samples_.clipping_ratio();
    out[kIntegralFlatRunCount]       = static_cast<double>(samples_.flat_run_count());
    out[kIntegralFlatRunLongest]     = samples_.flat_run_longest();
    out[kIntegralFlatRunMean]        = samples_.flat_run_mean();
    out[kIntegralFlatRunSampleRatio] = samples_.flat_run_sample_ratio();
    out[kIntegralFrameCount]         = static_cast<double>(samples_.frame_count());

    return kAudioIntegralFeatureCount;
}
