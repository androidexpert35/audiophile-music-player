// ─────────────────────────────────────────────────────────────────────────────
// cpu_affinity_policy.h
//
// CPU cluster selection and scheduling priority for the audio decode thread.
//
// This module is deliberately free of FFmpeg and JNI types so the policy can
// be unit-tested on the host.  The decode-load classification is pure; only
// bind_current_thread_for_decode_load() / configure_current_thread_priority()
// touch the OS.
//
// Policy summary (full rationale in cpu_affinity_policy.cpp):
//   • LIGHT load (lossless/lossy PCM ≤ 96 kHz)  → always the lowest-frequency
//     cluster; trivial for any core, maximum battery savings.
//   • HEAVY load (DSD or PCM > 96 kHz)          → lowest cluster if it clocks
//     ≥ 1.8 GHz (modern all-big SoC), otherwise the middle tier; the Prime
//     core is never targeted on ≥ 3-tier SoCs.
//   • Homogeneous SoCs are left to the kernel scheduler (no pinning).
// ─────────────────────────────────────────────────────────────────────────────

#pragma once

/** Computational cost class of the current audio stream. */
enum class DecodeLoad {
    LIGHT,   ///< Standard FLAC / MP3 / AAC / PCM at ≤ 96 kHz.
    HEAVY,   ///< DSD (on-the-fly PCM decimation) or PCM > 96 kHz.
};

/**
 * Classifies a stream into LIGHT vs HEAVY decode load.
 *
 * Pure function — host-testable.
 *
 * @param is_dsd          True when the codec is a DSD family decoder.
 * @param sample_rate_hz  Stream sample rate in Hz.
 */
[[nodiscard]] constexpr DecodeLoad classify_decode_load(
        bool is_dsd,
        int  sample_rate_hz) noexcept
{
    if (is_dsd) return DecodeLoad::HEAVY;
    // Above 96 kHz (192 / 352.8 / 384 kHz …) raw throughput alone can stress a
    // LITTLE core on older silicon.
    if (sample_rate_hz > 96'000) return DecodeLoad::HEAVY;
    return DecodeLoad::LIGHT;
}

/**
 * Pins the current thread to the CPU cluster matching `load`.
 *
 * @return true when an affinity mask was applied; false when pinning was
 *         skipped (homogeneous SoC, missing sysfs data, or syscall failure).
 */
bool bind_current_thread_for_decode_load(DecodeLoad load) noexcept;

/**
 * Raises the current thread's scheduling priority for real-time audio work.
 * Safe to call repeatedly; failures are logged and ignored.
 */
void configure_current_thread_priority() noexcept;
