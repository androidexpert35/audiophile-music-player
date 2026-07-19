// ─────────────────────────────────────────────────────────────────────────────
// audio_gain.h
//
// Single source of truth for the UI-position → amplitude-gain mapping used on
// every direct-USB output path.
//
// The UI supplies a linear slider position in [0, 1]; the wire paths apply one
// quadratic power taper (gain = position²) as the perceptual-loudness
// approximation.  Both consumers MUST use this function so the same UI
// position always produces the same loudness:
//
//   • DecoderToRingBridge::set_volume()          — native decoder pump path
//   • EngineSwapBridge nativeWriteToRingBuffer   — enhanced float32 DSP path
//
// Mapping reference:
//
//   position │ gain (pos²) │ equiv. dB
//   ─────────┼─────────────┼──────────
//   0.00     │  0.000 000  │  −∞  (mute)
//   0.25     │  0.062 500  │  −24.1 dB
//   0.50     │  0.250 000  │  −12.0 dB
//   0.75     │  0.562 500  │   −5.0 dB
//   1.00     │  1.000 000  │    0.0 dB  (exact unity — bit-perfect path)
//
// Positions outside [0, 1] are clamped, and the endpoints return exactly 0.0
// and 1.0 so mute stays a strict zero-multiply and full volume keeps the
// integer-only unity path in PcmWireFormatter byte-exact.
// ─────────────────────────────────────────────────────────────────────────────

#pragma once

/**
 * Converts a raw UI volume position in [0, 1] to the amplitude gain scalar
 * via the quadratic taper (gain = position²).
 */
[[nodiscard]] constexpr double ui_position_to_gain(double position) noexcept
{
    if (position <= 0.0) return 0.0;
    if (position >= 1.0) return 1.0;
    return position * position;
}

/** Float overload of ui_position_to_gain() for the pump thread's atomic. */
[[nodiscard]] constexpr float ui_position_to_gain(float position) noexcept
{
    if (position <= 0.0f) return 0.0f;
    if (position >= 1.0f) return 1.0f;
    return position * position;
}
