// ─────────────────────────────────────────────────────────────────────────────
// native_core_tests.cpp
//
// Host-side tests for the pure wire-format / state / policy logic of the
// native audio layer.  Everything here builds without Android, JNI, FFmpeg or
// libusb, and runs under ASan+UBSan (see tests/CMakeLists.txt).
// ─────────────────────────────────────────────────────────────────────────────

#include "audio_analysis_aggregator.h"
#include "audio_gain.h"
#include "cpu_affinity_policy.h"
#include "dop_formatter.h"
#include "native_dsd_formatter.h"
#include "pcm_wire_formatter.h"
#include "usb_handle_validation.h"
#include "usb_playback_state.h"

#include <array>
#include <cassert>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <limits>

namespace {

int32_t read_s32le(const uint8_t *source)
{
    uint32_t bits =
            static_cast<uint32_t>(source[0]) |
            (static_cast<uint32_t>(source[1]) << 8U) |
            (static_cast<uint32_t>(source[2]) << 16U) |
            (static_cast<uint32_t>(source[3]) << 24U);
    return static_cast<int32_t>(bits);
}

// ── PcmWireFormatter ─────────────────────────────────────────────────────────

void test_s16_unity_is_exact()
{
    const UsbPcmWireFormat wire{44100U, 2U, 4U, 32U};
    const PcmWireFormatter formatter(wire);
    const std::array<uint8_t, 8> input{
        0x00, 0x80,
        0xFF, 0xFF,
        0x01, 0x00,
        0xFF, 0x7F,
    };
    std::array<uint8_t, 16> output{};

    const auto result = formatter.format(
            input.data(), input.size(), PcmSourceEncoding::S16Le, 1.0,
            output.data(), output.size());

    assert(result.success);
    assert(result.bytes_written == output.size());
    assert(read_s32le(output.data()) == INT32_MIN);
    assert(read_s32le(output.data() + 4) == -65536);
    assert(read_s32le(output.data() + 8) == 65536);
    assert(read_s32le(output.data() + 12) == 2147418112);
}

void test_s32_unity_is_exact()
{
    const UsbPcmWireFormat wire{192000U, 2U, 4U, 32U};
    const PcmWireFormatter formatter(wire);
    // Two S32LE samples: 0x12345678 and INT32_MIN.
    const std::array<uint8_t, 8> input{
        0x78, 0x56, 0x34, 0x12,
        0x00, 0x00, 0x00, 0x80,
    };
    std::array<uint8_t, 8> output{};

    const auto result = formatter.format(
            input.data(), input.size(), PcmSourceEncoding::S32Le, 1.0,
            output.data(), output.size());

    assert(result.success);
    assert(result.bytes_written == output.size());
    // Full-scale unity on a 32-valid-bit endpoint must be a byte-exact copy.
    assert(std::memcmp(input.data(), output.data(), input.size()) == 0);
}

void test_s16_attenuation_uses_extra_bits()
{
    const UsbPcmWireFormat wire{44100U, 2U, 4U, 32U};
    const PcmWireFormatter formatter(wire);
    const std::array<uint8_t, 2> input{0x01, 0x00};
    std::array<uint8_t, 4> output{};

    const auto result = formatter.format(
            input.data(), input.size(), PcmSourceEncoding::S16Le, 0.5,
            output.data(), output.size());

    assert(result.success);
    assert(read_s32le(output.data()) == 32768);
}

void test_endpoint_padding_is_honoured()
{
    const UsbPcmWireFormat wire{48000U, 2U, 4U, 24U};
    const PcmWireFormatter formatter(wire);
    const std::array<uint8_t, 2> input{0x01, 0x00};
    std::array<uint8_t, 4> output{};

    const auto result = formatter.format(
            input.data(), input.size(), PcmSourceEncoding::S16Le, 0.5,
            output.data(), output.size());

    assert(result.success);
    assert((static_cast<uint32_t>(read_s32le(output.data())) & 0xFFU) == 0U);
}

void test_float_writer_uses_same_endpoint_quantization()
{
    const UsbPcmWireFormat wire{48000U, 2U, 4U, 24U};
    const PcmWireFormatter formatter(wire);
    const float input_sample = 0.5F;
    std::array<uint8_t, sizeof(float)> input{};
    std::memcpy(input.data(), &input_sample, sizeof(input_sample));
    std::array<uint8_t, 4> output{};

    const auto result = formatter.format_float32(
            input.data(), input.size(), 1.0, output.data(), output.size());

    assert(result.success);
    const auto sample = static_cast<uint32_t>(read_s32le(output.data()));
    assert((sample & 0xFFU) == 0U);
}

void test_float_writer_replaces_nan_with_silence()
{
    const UsbPcmWireFormat wire{48000U, 2U, 4U, 32U};
    const PcmWireFormatter formatter(wire);
    const float nan_sample = std::numeric_limits<float>::quiet_NaN();
    std::array<uint8_t, sizeof(float)> input{};
    std::memcpy(input.data(), &nan_sample, sizeof(nan_sample));
    std::array<uint8_t, 4> output{0xAA, 0xAA, 0xAA, 0xAA};

    const auto result = formatter.format_float32(
            input.data(), input.size(), 1.0, output.data(), output.size());

    assert(result.success);
    // A NaN bit pattern must never reach the DAC — it becomes digital silence.
    assert(read_s32le(output.data()) == 0);
}

void test_float_writer_saturates_out_of_range()
{
    const UsbPcmWireFormat wire{48000U, 2U, 4U, 32U};
    const PcmWireFormatter formatter(wire);
    const float loud = 2.0F;   // > full scale
    std::array<uint8_t, sizeof(float)> input{};
    std::memcpy(input.data(), &loud, sizeof(loud));
    std::array<uint8_t, 4> output{};

    const auto result = formatter.format_float32(
            input.data(), input.size(), 1.0, output.data(), output.size());

    assert(result.success);
    assert(read_s32le(output.data()) == INT32_MAX);
}

void test_invalid_buffer_is_rejected_atomically()
{
    const UsbPcmWireFormat wire{48000U, 2U, 4U, 32U};
    const PcmWireFormatter formatter(wire);
    const std::array<uint8_t, 2> input{0x01, 0x00};
    std::array<uint8_t, 3> output{0xAA, 0xAA, 0xAA};

    const auto result = formatter.format(
            input.data(), input.size(), PcmSourceEncoding::S16Le, 1.0,
            output.data(), output.size());

    assert(!result.success);
    assert(output[0] == 0xAA);
}

// ── UsbPlaybackStateMachine ──────────────────────────────────────────────────

void test_playback_state_machine()
{
    UsbPlaybackStateMachine machine;
    assert(machine.transition_to(UsbPlaybackState::Configured));
    assert(machine.transition_to(UsbPlaybackState::Priming));
    assert(machine.transition_to(UsbPlaybackState::StreamingNativeDsd));
    assert(machine.transition_to(UsbPlaybackState::SwitchingToDop));
    assert(machine.transition_to(UsbPlaybackState::Priming));
    assert(machine.transition_to(UsbPlaybackState::StreamingDop));
    assert(!machine.transition_to(UsbPlaybackState::StreamingPcm));
    assert(machine.transition_to(UsbPlaybackState::Stopping));
    assert(machine.transition_to(UsbPlaybackState::Stopped));
}

void test_playback_state_machine_rejects_cross_mode_jumps()
{
    // A PCM stream must not silently morph into a DSD transport.
    UsbPlaybackStateMachine machine;
    assert(machine.transition_to(UsbPlaybackState::Configured));
    assert(machine.transition_to(UsbPlaybackState::Priming));
    assert(machine.transition_to(UsbPlaybackState::StreamingPcm));
    assert(!machine.transition_to(UsbPlaybackState::StreamingNativeDsd));
    assert(!machine.transition_to(UsbPlaybackState::StreamingDop));
    assert(!machine.transition_to(UsbPlaybackState::SwitchingToDop));
    // Fail-closed: the illegal attempts must not have changed the state.
    assert(machine.state() == UsbPlaybackState::StreamingPcm);
}

// ── DoP framing ──────────────────────────────────────────────────────────────

void test_dop_markers_chain_across_chunks()
{
    const std::array<uint8_t, 8> input{
        0x11, 0x22, 0x33, 0x44,
        0x55, 0x66, 0x77, 0x88,
    };
    std::array<uint8_t, 16> output{};

    auto marker = format_dop_from_interleaved_dsd(
            input.data(), output.data(), 1U, kDopMarkerA);
    assert(marker == kDopMarkerB);
    marker = format_dop_from_interleaved_dsd(
            input.data() + 4U, output.data() + 8U, 1U, marker);
    assert(marker == kDopMarkerA);
    assert(output[3] == kDopMarkerA);
    assert(output[7] == kDopMarkerA);
    assert(output[11] == kDopMarkerB);
    assert(output[15] == kDopMarkerB);
}

// ── Native DSD_U32LE framing ─────────────────────────────────────────────────

void test_native_dsd_planar_msbf_is_chronological_copy()
{
    const std::array<uint8_t, 4> left{0x10, 0x20, 0x30, 0x40};
    const std::array<uint8_t, 4> right{0x50, 0x60, 0x70, 0x80};
    std::array<uint8_t, 8> output{};

    format_native_dsd_stereo_msbf(left.data(), right.data(), output.data(), 1U);

    // MSBF output is a straight chronological copy: first DSD byte in time at
    // the lowest output address, left slot then right slot.
    assert(std::memcmp(output.data(),     left.data(),  4U) == 0);
    assert(std::memcmp(output.data() + 4, right.data(), 4U) == 0);
}

void test_native_dsd_lsbf_bit_reverses_every_byte()
{
    const std::array<uint8_t, 4> left{0x80, 0x01, 0xF0, 0xAA};
    const std::array<uint8_t, 4> right{0x0F, 0x55, 0xC3, 0x3C};
    std::array<uint8_t, 8> output{};

    format_native_dsd_stereo_lsbf(left.data(), right.data(), output.data(), 1U);

    for (std::size_t i = 0; i < 4U; ++i) {
        assert(output[i]     == bit_reverse_byte(left[i]));
        assert(output[4 + i] == bit_reverse_byte(right[i]));
    }
    // Spot-check the reversal itself.
    assert(bit_reverse_byte(0x80U) == 0x01U);
    assert(bit_reverse_byte(0xF0U) == 0x0FU);
    assert(bit_reverse_byte(0xAAU) == 0x55U);
}

void test_native_dsd_interleaved_deinterleaves_channels()
{
    // Interleaved layout: L0 R0 L1 R1 L2 R2 L3 R3.
    const std::array<uint8_t, 8> input{
        0x10, 0x50, 0x20, 0x60, 0x30, 0x70, 0x40, 0x80,
    };
    std::array<uint8_t, 8> output{};

    format_native_dsd_from_interleaved_msbf(input.data(), output.data(), 1U);

    const std::array<uint8_t, 8> expected{
        0x10, 0x20, 0x30, 0x40,   // left slot, chronological
        0x50, 0x60, 0x70, 0x80,   // right slot, chronological
    };
    assert(output == expected);
}

// ── Shared volume taper ──────────────────────────────────────────────────────

void test_ui_position_to_gain_quadratic_taper()
{
    // Endpoints are exact so mute stays a strict zero and full volume keeps
    // the integer-only unity path bit-perfect.
    assert(ui_position_to_gain(0.0) == 0.0);
    assert(ui_position_to_gain(1.0) == 1.0);
    assert(ui_position_to_gain(-0.5) == 0.0);
    assert(ui_position_to_gain(1.5) == 1.0);
    assert(std::fabs(ui_position_to_gain(0.5) - 0.25) < 1e-12);
    assert(std::fabs(ui_position_to_gain(0.25) - 0.0625) < 1e-12);
    // Float overload matches the double overload semantics.
    assert(ui_position_to_gain(0.5f) == 0.25f);
    assert(ui_position_to_gain(2.0f) == 1.0f);
}

// ── Handle validation ────────────────────────────────────────────────────────

void test_native_handle_validation()
{
    // Null and error sentinels are rejected.
    assert(!is_valid_native_handle(0, kUsbDriverLowestErrorSentinel));
    for (int64_t code = -1; code >= kUsbDriverLowestErrorSentinel; --code) {
        assert(!is_valid_native_handle(code, kUsbDriverLowestErrorSentinel));
    }
    // Values below the sentinel range are pointers, not errors.
    assert(is_valid_native_handle(kUsbDriverLowestErrorSentinel - 1,
                                  kUsbDriverLowestErrorSentinel));
    // Positive heap pointers are accepted.
    assert(is_valid_native_handle(0x7000'0000'1000LL, kUsbDriverLowestErrorSentinel));
    // ARM64 MTE-tagged pointers appear as large-negative jlongs and MUST be
    // accepted — this is the regression the shared helper exists to prevent.
    assert(is_valid_native_handle(static_cast<int64_t>(0xb4000075'1ff31100ULL),
                                  kUsbDriverLowestErrorSentinel));
    // The engine-swap sentinel range is wider (-6..-1).
    assert(!is_valid_native_handle(-6, kEngineSwapLowestErrorSentinel));
    assert(is_valid_native_handle(-7, kEngineSwapLowestErrorSentinel));
}

// ── Decode-load classification ───────────────────────────────────────────────

void test_classify_decode_load()
{
    assert(classify_decode_load(/*is_dsd=*/true, 2'822'400) == DecodeLoad::HEAVY);
    assert(classify_decode_load(/*is_dsd=*/true, 44'100) == DecodeLoad::HEAVY);
    assert(classify_decode_load(false, 44'100) == DecodeLoad::LIGHT);
    assert(classify_decode_load(false, 96'000) == DecodeLoad::LIGHT);
    assert(classify_decode_load(false, 192'000) == DecodeLoad::HEAVY);
    assert(classify_decode_load(false, 384'000) == DecodeLoad::HEAVY);
}

// -- Class S analysis aggregation --------------------------------------------

bool analysis_close(double actual, double expected, double tolerance)
{
    return std::fabs(actual - expected) <= tolerance;
}

void test_parse_measured_double_rejects_non_numbers()
{
    double value = 0.0;

    assert(parse_measured_double("12.5", &value));
    assert(analysis_close(value, 12.5, 1e-12));

    // astats prints a silent level as "-inf" and aspectralstats can emit "nan";
    // neither is a measurement, so both must be rejected rather than averaged.
    assert(!parse_measured_double("-inf", &value));
    assert(!parse_measured_double("inf", &value));
    assert(!parse_measured_double("nan", &value));

    assert(!parse_measured_double(nullptr, &value));
    assert(!parse_measured_double("", &value));
    assert(!parse_measured_double("abc", &value));
    assert(!parse_measured_double("1.5x", &value));

    // Trailing whitespace is tolerated; the value survives untouched.
    value = 0.0;
    assert(parse_measured_double("3.25\n", &value));
    assert(analysis_close(value, 3.25, 1e-12));

    // A rejected parse must not clobber the value the caller already held.
    assert(!parse_measured_double("nan", &value));
    assert(analysis_close(value, 3.25, 1e-12));
}

void test_running_mean_ignores_non_finite()
{
    RunningMean mean;
    assert(!mean.has_value());
    assert(std::isnan(mean.value()));

    mean.add(10.0);
    mean.add(std::numeric_limits<double>::quiet_NaN());
    mean.add(-std::numeric_limits<double>::infinity());
    mean.add(20.0);

    assert(mean.has_value());
    assert(mean.count() == 2);
    assert(analysis_close(mean.value(), 15.0, 1e-12));
}

void test_stereo_energy_identical_channels_have_no_side()
{
    const std::array<float, 8> stereo{
        0.5F, 0.5F,
        0.5F, 0.5F,
        0.5F, 0.5F,
        0.5F, 0.5F,
    };
    StereoEnergyAccumulator accumulator(2);
    accumulator.add_interleaved(stereo.data(), 4);

    assert(accumulator.frame_count() == 4);
    // Half scale is -6.0206 dBFS in both channels and in mid; side is silent.
    assert(analysis_close(accumulator.left_rms_dbfs(), -6.0206, 1e-3));
    assert(analysis_close(accumulator.right_rms_dbfs(), -6.0206, 1e-3));
    assert(analysis_close(accumulator.mid_rms_dbfs(), -6.0206, 1e-3));
    assert(analysis_close(accumulator.side_rms_dbfs(), kAudioAnalysisSilenceDbfs, 1e-9));
    assert(analysis_close(accumulator.correlation(), 1.0, 1e-12));
}

void test_stereo_energy_out_of_phase_is_all_side()
{
    const std::array<float, 8> stereo{
         0.5F, -0.5F,
         0.5F, -0.5F,
         0.5F, -0.5F,
         0.5F, -0.5F,
    };
    StereoEnergyAccumulator accumulator(2);
    accumulator.add_interleaved(stereo.data(), 4);

    assert(analysis_close(accumulator.mid_rms_dbfs(), kAudioAnalysisSilenceDbfs, 1e-9));
    assert(analysis_close(accumulator.side_rms_dbfs(), -6.0206, 1e-3));
    assert(analysis_close(accumulator.correlation(), -1.0, 1e-12));
}

void test_stereo_energy_uncorrelated_channels_split_evenly()
{
    const std::array<float, 8> stereo{
         1.0F,  1.0F,
         1.0F, -1.0F,
        -1.0F,  1.0F,
        -1.0F, -1.0F,
    };
    StereoEnergyAccumulator accumulator(2);
    accumulator.add_interleaved(stereo.data(), 4);

    assert(analysis_close(accumulator.correlation(), 0.0, 1e-12));
    // Half the energy in mid, half in side.
    assert(analysis_close(accumulator.mid_rms_dbfs(), -3.0103, 1e-3));
    assert(analysis_close(accumulator.side_rms_dbfs(), -3.0103, 1e-3));
}

void test_stereo_energy_mono_source_is_perfectly_correlated()
{
    const std::array<float, 4> mono{0.25F, 0.25F, 0.25F, 0.25F};
    StereoEnergyAccumulator accumulator(1);
    accumulator.add_interleaved(mono.data(), 4);

    assert(accumulator.frame_count() == 4);
    assert(analysis_close(accumulator.left_rms_dbfs(), accumulator.right_rms_dbfs(), 1e-12));
    assert(analysis_close(accumulator.side_rms_dbfs(), kAudioAnalysisSilenceDbfs, 1e-9));
    assert(analysis_close(accumulator.correlation(), 1.0, 1e-12));
}

void test_stereo_energy_silent_channel_has_no_correlation()
{
    const std::array<float, 4> stereo{
        0.0F, 0.5F,
        0.0F, 0.5F,
    };
    StereoEnergyAccumulator accumulator(2);
    accumulator.add_interleaved(stereo.data(), 2);

    assert(analysis_close(accumulator.left_rms_dbfs(), kAudioAnalysisSilenceDbfs, 1e-9));
    assert(std::isnan(accumulator.correlation()));

    // Nothing accumulated at all is "not measured", not silence.
    const StereoEnergyAccumulator empty(2);
    assert(std::isnan(empty.left_rms_dbfs()));
    assert(std::isnan(empty.correlation()));
}

void test_stereo_energy_skips_non_finite_frames()
{
    const float bad = std::numeric_limits<float>::quiet_NaN();
    const std::array<float, 6> stereo{
        0.5F, 0.5F,
        bad,  0.5F,
        0.5F, 0.5F,
    };
    StereoEnergyAccumulator accumulator(2);
    accumulator.add_interleaved(stereo.data(), 3);

    assert(accumulator.frame_count() == 2);
    assert(analysis_close(accumulator.left_rms_dbfs(), -6.0206, 1e-3));
}

void test_analysis_aggregator_averages_windows_and_keeps_last_levels()
{
    const double nan_value = std::numeric_limits<double>::quiet_NaN();

    AudioAnalysisAggregator aggregator(2);
    aggregator.note_window();
    aggregator.add_spectral_channel(1000.0, 15000.0, -0.5);
    aggregator.add_spectral_channel(2000.0, 17000.0, -0.3);
    aggregator.set_level_snapshot(-90.0, 0.001);

    aggregator.note_window();
    // A window where one statistic was missing must not poison the others.
    aggregator.add_spectral_channel(nan_value, 18000.0, nan_value);
    // astats is cumulative, so the newest snapshot replaces the previous one...
    aggregator.set_level_snapshot(-92.0, 0.002);
    // ...but a missing snapshot must never clear what was already measured.
    aggregator.set_level_snapshot(nan_value, nan_value);

    const std::array<float, 4> stereo{0.5F, 0.5F, 0.5F, 0.5F};
    aggregator.add_samples(stereo.data(), 2);

    std::array<double, kAudioAnalysisFeatureCount> features{};
    assert(aggregator.write_features(features.data(), features.size()) ==
           kAudioAnalysisFeatureCount);

    assert(analysis_close(features[kFeatureSpectralCentroidHz], 1500.0, 1e-9));
    assert(analysis_close(features[kFeatureSpectralRolloffHz], 50000.0 / 3.0, 1e-9));
    assert(analysis_close(features[kFeatureSpectralSlope], -0.4, 1e-9));
    assert(analysis_close(features[kFeatureNoiseFloorDbfs], -92.0, 1e-12));
    assert(analysis_close(features[kFeatureDcOffset], 0.002, 1e-12));
    assert(analysis_close(features[kFeatureInterChannelCorrelation], 1.0, 1e-12));
    assert(analysis_close(features[kFeatureWindowCount], 2.0, 1e-12));
    assert(analysis_close(features[kFeatureFrameCount], 2.0, 1e-12));
}

void test_analysis_aggregator_reports_unmeasured_as_nan()
{
    const AudioAnalysisAggregator aggregator(2);
    std::array<double, kAudioAnalysisFeatureCount> features{};
    assert(aggregator.write_features(features.data(), features.size()) ==
           kAudioAnalysisFeatureCount);

    assert(std::isnan(features[kFeatureSpectralRolloffHz]));
    assert(std::isnan(features[kFeatureSpectralCentroidHz]));
    assert(std::isnan(features[kFeatureSpectralSlope]));
    assert(std::isnan(features[kFeatureNoiseFloorDbfs]));
    assert(std::isnan(features[kFeatureDcOffset]));
    assert(std::isnan(features[kFeatureLeftRmsDbfs]));
    assert(std::isnan(features[kFeatureInterChannelCorrelation]));
    assert(analysis_close(features[kFeatureWindowCount], 0.0, 1e-12));
    assert(analysis_close(features[kFeatureFrameCount], 0.0, 1e-12));

    // A short output array is refused atomically rather than partially filled.
    std::array<double, kAudioAnalysisFeatureCount - 1> too_small{};
    assert(aggregator.write_features(too_small.data(), too_small.size()) == 0);
    assert(aggregator.write_features(nullptr, kAudioAnalysisFeatureCount) == 0);
}

} // namespace

int main()
{
    test_s16_unity_is_exact();
    test_s32_unity_is_exact();
    test_s16_attenuation_uses_extra_bits();
    test_endpoint_padding_is_honoured();
    test_float_writer_uses_same_endpoint_quantization();
    test_float_writer_replaces_nan_with_silence();
    test_float_writer_saturates_out_of_range();
    test_invalid_buffer_is_rejected_atomically();
    test_playback_state_machine();
    test_playback_state_machine_rejects_cross_mode_jumps();
    test_dop_markers_chain_across_chunks();
    test_native_dsd_planar_msbf_is_chronological_copy();
    test_native_dsd_lsbf_bit_reverses_every_byte();
    test_native_dsd_interleaved_deinterleaves_channels();
    test_ui_position_to_gain_quadratic_taper();
    test_native_handle_validation();
    test_classify_decode_load();
    test_parse_measured_double_rejects_non_numbers();
    test_running_mean_ignores_non_finite();
    test_stereo_energy_identical_channels_have_no_side();
    test_stereo_energy_out_of_phase_is_all_side();
    test_stereo_energy_uncorrelated_channels_split_evenly();
    test_stereo_energy_mono_source_is_perfectly_correlated();
    test_stereo_energy_silent_channel_has_no_correlation();
    test_stereo_energy_skips_non_finite_frames();
    test_analysis_aggregator_averages_windows_and_keeps_last_levels();
    test_analysis_aggregator_reports_unmeasured_as_nan();
    std::cout << "audiophile_native_core_tests: all tests passed\n";
    return 0;
}
