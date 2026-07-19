// ─────────────────────────────────────────────────────────────────────────────
// dop_formatter.h
//
// DoP 1.1 (DSD over PCM) framing functions.
//
// Converts raw DSD bitstream bytes into 32-bit little-endian PCM words
// suitable for transmission through a USB isochronous OUT transfer to a UAC2
// DAC that advertises DoP capability.
//
// ── DoP 1.1 word layout ──────────────────────────────────────────────────────
//
// Each DSD "frame" contains 16 DSD bits per channel, packed into one 32-bit
// PCM word per channel.  The canonical bit-field layout, expressed in
// mathematical big-endian notation (bit 31 = MSB), is:
//
//   Bits [31:24]  DoP marker byte     — alternates 0x05 / 0xFA every frame.
//   Bits [23:16]  DSD byte 0          — first  DSD byte of the 16-bit block.
//   Bits [15: 8]  DSD byte 1          — second DSD byte of the 16-bit block.
//   Bits [ 7: 0]  0x00                — zero-padded LSB (mandatory per § 4).
//
// In little-endian memory (byte addresses n, n+1, n+2, n+3):
//
//   n + 0  ←  0x00               (bits  7: 0)
//   n + 1  ←  DSD byte 1         (bits 15: 8)
//   n + 2  ←  DSD byte 0         (bits 23:16)
//   n + 3  ←  Marker (0x05/0xFA) (bits 31:24)
//
// ── Stereo interleaving ───────────────────────────────────────────────────────
//
// DACs expect interleaved PCM samples: [L_word][R_word][L_word][R_word] …
// Both channels within the SAME frame carry the SAME marker byte.
// The marker toggles once per stereo frame (i.e., after both L and R are written).
//
//   Frame 0: L=[0x05|dsd_l0|dsd_l1|0x00]  R=[0x05|dsd_r0|dsd_r1|0x00]
//   Frame 1: L=[0xFA|dsd_l2|dsd_l3|0x00]  R=[0xFA|dsd_r2|dsd_r3|0x00]
//   Frame 2: L=[0x05|dsd_l4|dsd_l5|0x00]  R=[0x05|dsd_r4|dsd_r5|0x00]
//   …
//
// ── Marker toggle — why 0x05 XOR 0xFF = 0xFA is branch-free ─────────────────
//
//   kDopMarkerA (0x05) XOR kToggleMask (0xFF) = 0xFA  ✓
//   kDopMarkerB (0xFA) XOR kToggleMask (0xFF) = 0x05  ✓
//
// One EOR instruction on ARM64 — no branch predictor pressure, no stall.
//
// ── DSD bit ordering ─────────────────────────────────────────────────────────
//
// These functions assume MSBF (Most Significant Bit First) DSD byte ordering,
// which is the native output of FFmpeg's DSD decoders
// (AV_CODEC_ID_DSD_MSBF / AV_CODEC_ID_DSD_MSBF_PLANAR).
//
// For LSBF (Least Significant Bit First) DSD, each byte must be bit-reversed
// before calling these functions.  Bit reversal is outside the scope of this
// formatter; handle it in the FFmpeg bridge layer.
//
// ── Endianness guarantee ─────────────────────────────────────────────────────
//
// The output byte order is explicitly little-endian regardless of the host
// CPU's native byte order.  All Android ABIs (arm64-v8a, armeabi-v7a, x86_64)
// are little-endian, so the write_le32() helper inside the .cpp compiles to
// a single 32-bit store instruction at -O2+.  Big-endian hosts are handled
// by a portable byte-by-byte fallback, at the cost of three extra shifts.
//
// ── Buffer size contract ─────────────────────────────────────────────────────
//
//   Input  (per channel, planar):   2 × num_dop_frames  bytes
//   Input  (interleaved variant):   4 × num_dop_frames  bytes
//   Output (interleaved PCM):       8 × num_dop_frames  bytes
//
// Use compute_dop_output_bytes() and compute_dsd_input_bytes_per_channel()
// to derive these sizes at call sites.
//
// ─────────────────────────────────────────────────────────────────────────────

#pragma once

#include <cstddef>
#include <cstdint>

// ─────────────────────────────────────────────────────────────────────────────
// DoP marker constants
// ─────────────────────────────────────────────────────────────────────────────

/// First-phase DoP 1.1 marker byte (bits [31:24] of the PCM word).
/// Every even-numbered stereo frame uses this value.
inline constexpr uint8_t kDopMarkerA = 0x05u;

/// Second-phase DoP 1.1 marker byte (bits [31:24] of the PCM word).
/// Every odd-numbered stereo frame uses this value.
inline constexpr uint8_t kDopMarkerB = 0xFAu;

/// XOR mask that toggles between kDopMarkerA and kDopMarkerB.
/// kDopMarkerA ^ kDopMarkerB = 0x05 ^ 0xFA = 0xFF.
/// One EOR instruction on ARM64 — no branch.
inline constexpr uint8_t kDopToggleMask = kDopMarkerA ^ kDopMarkerB;  // 0xFF

// ─────────────────────────────────────────────────────────────────────────────
// Buffer size helpers
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Returns the number of raw DSD bytes required per channel for `num_dop_frames`
 * DoP frames.  Each frame consumes exactly 2 DSD bytes per channel (= 16 bits).
 *
 * @param num_dop_frames  Number of DoP stereo frames to format.
 * @return                Input buffer size in bytes, per channel.
 */
[[nodiscard]] constexpr std::size_t
compute_dsd_input_bytes_per_channel(std::size_t num_dop_frames) noexcept {
    return num_dop_frames * 2u;
}

/**
 * Returns the total output PCM buffer size in bytes for `num_dop_frames`.
 *
 * Each frame produces two 32-bit words (L + R) = 8 bytes per frame.
 *
 * @param num_dop_frames  Number of DoP stereo frames to format.
 * @return                Output buffer size in bytes (interleaved L/R PCM).
 */
[[nodiscard]] constexpr std::size_t
compute_dop_output_bytes(std::size_t num_dop_frames) noexcept {
    return num_dop_frames * 8u;
}

// ─────────────────────────────────────────────────────────────────────────────
// Planar DSD input → DoP PCM output
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Pack planar MSBF DSD bytes into a DoP 1.1 stereo interleaved PCM stream.
 *
 * ### Input format (planar)
 *
 * `dsd_left`  and `dsd_right` are independent byte arrays with `2 × num_dop_frames`
 * bytes each.  In each channel array, consecutive pairs of bytes form one
 * 16-bit DSD block:
 *
 *   dsd_left[2i+0]  → DSD bits [15:8] for frame i, left channel  (first byte  received)
 *   dsd_left[2i+1]  → DSD bits [ 7:0] for frame i, left channel  (second byte received)
 *
 * The same indexing applies to `dsd_right`.
 *
 * ### Output format (interleaved 32-bit LE PCM)
 *
 * For each frame i, `pcm_out` receives 8 bytes:
 *
 *   pcm_out[8i+0 … 8i+3]  ← Left  channel word (LE)
 *   pcm_out[8i+4 … 8i+7]  ← Right channel word (LE)
 *
 * ### Marker chaining across calls
 *
 * The function returns the **next** marker byte to use.  Pass this return value
 * as `initial_marker` on the subsequent call to maintain the correct 0x05/0xFA
 * alternation across ring-buffer segment boundaries:
 *
 * @code
 *   uint8_t marker = kDopMarkerA;   // initialised once per playback session
 *   marker = format_dop_stereo(dsd_l, dsd_r, pcm, frames, marker);
 *   ring->push(pcm, compute_dop_output_bytes(frames));
 *   // Next call resumes with the returned marker.
 * @endcode
 *
 * ### Threading
 *
 * This function is pure (no shared mutable state).  It is safe to call from
 * any thread.  The typical call site is the FFmpeg decoder thread (producer).
 *
 * ### Preconditions (checked only in debug builds)
 *
 *   • `dsd_left`  is non-null when `num_dop_frames > 0`
 *   • `dsd_right` is non-null when `num_dop_frames > 0`
 *   • `pcm_out`   is non-null when `num_dop_frames > 0`
 *   • `dsd_left`  has at least `2 × num_dop_frames` bytes
 *   • `dsd_right` has at least `2 × num_dop_frames` bytes
 *   • `pcm_out`   has at least `8 × num_dop_frames` bytes
 *   • `initial_marker` is either `kDopMarkerA` (0x05) or `kDopMarkerB` (0xFA)
 *
 * @param dsd_left       Planar left-channel  DSD bytes (MSBF).
 * @param dsd_right      Planar right-channel DSD bytes (MSBF).
 * @param pcm_out        Destination buffer for interleaved 32-bit LE PCM words.
 * @param num_dop_frames Number of stereo DoP frames to encode.
 * @param initial_marker Starting DoP marker; pass `kDopMarkerA` for a fresh
 *                       session or the return value of the previous call to
 *                       continue correct marker alternation.
 * @return               The marker byte to use for the **next** call.
 *                       Store and forward this value between successive calls.
 */
uint8_t format_dop_stereo(
        const uint8_t *__restrict__ dsd_left,
        const uint8_t *__restrict__ dsd_right,
        uint8_t       *__restrict__ pcm_out,
        std::size_t                  num_dop_frames,
        uint8_t                      initial_marker) noexcept;

// ─────────────────────────────────────────────────────────────────────────────
// Interleaved DSD input → DoP PCM output
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Pack interleaved MSBF DSD bytes into a DoP 1.1 stereo interleaved PCM stream.
 *
 * ### Input format (interleaved by byte, per channel)
 *
 * `dsd_interleaved` is a single buffer where samples alternate per byte:
 *
 *   Byte 4i+0  → dsd_left  byte 0 for frame i   (DSD bits [15:8], left)
 *   Byte 4i+1  → dsd_right byte 0 for frame i   (DSD bits [15:8], right)
 *   Byte 4i+2  → dsd_left  byte 1 for frame i   (DSD bits [ 7:0], left)
 *   Byte 4i+3  → dsd_right byte 1 for frame i   (DSD bits [ 7:0], right)
 *
 * This layout matches the output of FFmpeg's interleaved DSD demuxers
 * (e.g., DSDIFF / DSF files decoded in non-planar mode).
 *
 * ### Output format
 *
 * Identical to `format_dop_stereo()` — interleaved 32-bit LE PCM.
 *
 * ### Marker chaining
 *
 * Identical semantics to `format_dop_stereo()` — return the value as
 * `initial_marker` for the next invocation.
 *
 * @param dsd_interleaved Interleaved DSD bytes (MSBF), 4 bytes per DoP frame.
 * @param pcm_out         Destination buffer for interleaved 32-bit LE PCM.
 * @param num_dop_frames  Number of stereo DoP frames to encode.
 * @param initial_marker  Starting DoP marker (`kDopMarkerA` or `kDopMarkerB`).
 * @return                The marker byte to use for the next call.
 */
uint8_t format_dop_from_interleaved_dsd(
        const uint8_t *__restrict__ dsd_interleaved,
        uint8_t       *__restrict__ pcm_out,
        std::size_t                  num_dop_frames,
        uint8_t                      initial_marker) noexcept;

