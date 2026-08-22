#pragma once

#include <cstdint>

/**
 * Selects the USB framing applied to the canonical interleaved DSD byte stream.
 */
enum class DsdWireMode : uint8_t {
    Native,
    Dop,
};

/**
 * Idle/underrun byte for a **native DSD** wire stream.
 *
 * A 1-bit delta-sigma stream carries amplitude in its *density* of ones, so
 * `0x00` is not silence there — a run of zero bytes is a full-scale negative
 * DC level. Handing that to a DAC that does not soft-mute its DSD path
 * reconstructs as a step to the negative rail: the loud tick heard at the
 * start of native-DSD playback on Snowsky/Cirrus dongles (XMOS designs such as
 * the FiiO KA5 mute internally and hide it). `0x69` (`01101001`) is the
 * canonical DSD silence pattern — equal ones and zeros, hence zero mean — and
 * is the same value the Linux USB-audio driver writes for the DSD_U8/U16/U32
 * formats.
 *
 * Bit order does not matter: the byte-reversed pattern `0x96` is equally
 * balanced, so MSBF and LSBF DACs both idle silently on `0x69`.
 */
constexpr uint8_t kNativeDsdSilenceByte = 0x69U;

/**
 * Idle/underrun byte for PCM and for DoP.
 *
 * PCM silence is all-zero by definition. A DoP frame of zeros carries no valid
 * `0x05`/`0xFA` marker, so a DoP-capable DAC leaves DSD mode and interprets the
 * payload as PCM — which is zero, i.e. silence. Both cases are therefore mute.
 */
constexpr uint8_t kPcmSilenceByte = 0x00U;
