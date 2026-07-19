// ─────────────────────────────────────────────────────────────────────────────
// native_dsd_formatter.h
//
// Step 12 — Native DSD (DSD_U32LE) formatter for UAC2 isochronous OUT transfers.
//
// Converts planar or interleaved DSD bitstream bytes into 32-bit little-endian
// slot pairs suitable for direct transmission to a NativeTypeIV or NativeTypeIRaw
// UAC2 DAC (no DoP markers, no PCM framing, no zero-padding).
//
// ── DSD_U32LE stereo output frame layout ──────────────────────────────────────
//
// Each "native DSD output frame" occupies 8 consecutive bytes in the ISO OUT
// buffer — identical byte footprint to a DoP stereo frame, but every bit
// carries real DSD data:
//
//   out[0]  ← L DSD byte 0  (DSD bits [31:24], MSBF: bit 7 = first DSD clock)
//   out[1]  ← L DSD byte 1  (DSD bits [23:16])
//   out[2]  ← L DSD byte 2  (DSD bits [15: 8])
//   out[3]  ← L DSD byte 3  (DSD bits  [7: 0])
//   out[4]  ← R DSD byte 0  (same layout, right channel)
//   out[5]  ← R DSD byte 1
//   out[6]  ← R DSD byte 2
//   out[7]  ← R DSD byte 3
//
// The 4-byte per-channel block is interpretable as a 32-bit LE integer:
//   uint32_t = byte0 | (byte1 << 8) | (byte2 << 16) | (byte3 << 24)
//
// On little-endian hosts (all Android ABIs), writing DSD bytes in chronological
// order to memory IS correct DSD_U32LE packing — no byte-swap is needed for
// the MSBF path.
//
// ── Bit ordering — MSBF vs LSBF ──────────────────────────────────────────────
//
//   MSBF (Most Significant Bit First):
//     Bit 7 of DSD byte 0 = the first DSD clock pulse in time.
//     This is the native output of FFmpeg's DSD decoders
//     (AV_CODEC_ID_DSD_MSBF / AV_CODEC_ID_DSD_MSBF_PLANAR).
//     Expected by: FiiO K9 Pro / Q7 / BTR7, RME ADI-2 series,
//                  Topping DX3 Pro+ / DX5, Matrix Audio X-SABRE 3.
//     → No byte transformation needed; DSD bytes are forwarded as-is.
//
//   LSBF (Least Significant Bit First):
//     Bit 0 of DSD byte 0 = the first DSD clock pulse in time.
//     Required by some legacy Sony, Denon, and older iFi USB DACs.
//     → Each DSD byte is bit-reversed before packing via bit_reverse_byte().
//       On ARM64, bit_reverse_byte() compiles to RBIT + LSR #24 — 2 cycles.
//
// ── Comparison with DoP (dop_formatter.h) ────────────────────────────────────
//
//   DoP:        2 DSD bytes/channel/frame → 8-byte output word (2 DSD + 1 marker + 1 pad)
//   Native DSD: 4 DSD bytes/channel/frame → 8-byte output word (4 DSD, no overhead)
//
//   Output buffer footprint is identical: both produce 8 bytes per stereo frame,
//   so the ring buffer sizing and USB transfer pool layout are the same.
//   The difference is in how many DSD bytes are consumed from the ring per frame.
//
// ── Zero-overhead formatter dispatch ─────────────────────────────────────────
//
// The DsdFrameFormatter variant wraps DopFormatterFunctor, NativeDsdMsbfFormatter,
// and NativeDsdLsbfFormatter into a std::variant.  All three expose the same
// operator() call signature.
//
// Dispatching via std::visit<> with a fixed 3-type variant compiles at -O3 to:
//   - Load the variant's discriminant index (1 byte)
//   - 2-entry comparison chain or small jump table → DIRECT function call
//
// Unlike a raw function pointer (which always requires an indirect branch BLRI),
// direct calls within std::visit are branch-predictor-friendly and fully inlinable,
// yielding zero extra overhead over a hard-coded format path on the hot iso callback.
//
// ── Buffer size contract (per formatter call) ────────────────────────────────
//
//   Input (planar, per channel):     num_frames × input_bytes_per_frame
//     DopFormatterFunctor:           num_frames × 2  bytes / channel
//     NativeDsdMsbfFormatter:        num_frames × 4  bytes / channel
//     NativeDsdLsbfFormatter:        num_frames × 4  bytes / channel
//
//   Output (all formatters, shared): num_frames × 8  bytes  (4 bytes L + 4 bytes R)
//
//   Use compute_native_dsd_output_bytes()         for output sizing.
//   Use compute_native_dsd_input_bytes_per_channel() for native DSD input sizing.
//   Use compute_dsd_input_bytes_per_channel()     (dop_formatter.h) for DoP input sizing.
//
// ─────────────────────────────────────────────────────────────────────────────

#pragma once

#include <cassert>
#include <cstddef>
#include <cstdint>
#include <variant>

#include "dop_formatter.h"   // DopFormatterFunctor wraps format_dop_stereo; kDopMarkerA

// ─────────────────────────────────────────────────────────────────────────────
// Bit-order classification
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Determines the within-byte DSD bit ordering expected by the connected DAC.
 *
 * Selects whether DSD bytes arrive at the USB endpoint with the first DSD clock
 * pulse in the MSB (bit 7) or LSB (bit 0) of each byte.
 */
enum class NativeDsdBitOrder : uint8_t {
    /**
     * Most Significant Bit First.
     *
     * Bit 7 of each DSD byte carries the earliest DSD clock pulse in time.
     * This matches FFmpeg's DSD_MSBF_PLANAR / DSD_MSBF decoder output directly.
     * No per-byte transformation is applied; DSD bytes are forwarded as-is.
     *
     * Expected by: FiiO K9 Pro / Q7 / BTR7, RME ADI-2 DAC FS / ADI-2 Pro,
     *              Topping DX3 Pro+ / DX5, Matrix Audio X-SABRE 3,
     *              Gustard X26 Pro.
     */
    Msbf = 0,

    /**
     * Least Significant Bit First.
     *
     * Bit 0 of each DSD byte carries the earliest DSD clock pulse in time.
     * Each input byte is bit-reversed via bit_reverse_byte() before packing.
     *
     * Required by a minority of older Sony HiRes Audio DACs and some legacy
     * Denon / Marantz USB Audio interfaces.
     */
    Lsbf = 1,
};

// ─────────────────────────────────────────────────────────────────────────────
// bit_reverse_byte — per-byte DSD bit-order inversion
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Reverse all 8 bits of a DSD byte, converting between MSBF and LSBF ordering.
 *
 * ### Compilation result on ARM64
 *
 * Clang / GCC with -O2+ recognises this three-XOR pattern and emits:
 *   RBIT  Wn, Wn    — 32-bit bit-reverse (1 cycle)
 *   LSR   Wn, Wn, #24
 * Total: 2 instructions, 1–2 cycles on Cortex-A75/A78.
 *
 * ### Why no lookup table
 *
 * A 256-entry lookup table has a 256-byte footprint and requires a cache-line
 * load on every call.  The three-XOR bitwise form uses no memory bandwidth and
 * is optimal for the per-byte throughput of DSD64–DSD512 streams.
 *
 * @param b  DSD byte to bit-reverse.
 * @return   Byte with all 8 bits reversed.
 */
[[nodiscard, gnu::always_inline]]
static inline uint8_t bit_reverse_byte(uint8_t b) noexcept
{
    // Swap nibbles (4-bit halves).
    b = static_cast<uint8_t>(((b & 0xF0u) >> 4u) | ((b & 0x0Fu) << 4u));
    // Swap bit pairs within each nibble.
    b = static_cast<uint8_t>(((b & 0xCCu) >> 2u) | ((b & 0x33u) << 2u));
    // Swap odd/even bits within each pair.
    b = static_cast<uint8_t>(((b & 0xAAu) >> 1u) | ((b & 0x55u) << 1u));
    return b;
}

// ─────────────────────────────────────────────────────────────────────────────
// Buffer size helpers
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Returns the number of raw DSD bytes required per channel for `num_frames`
 * native DSD 32-bit output frames.
 *
 * Each native DSD frame packs 32 DSD bits (4 bytes) per channel; this is also
 * the input consumption rate for NativeDsdMsbfFormatter and NativeDsdLsbfFormatter.
 *
 * @param num_frames  Number of native DSD stereo output frames.
 * @return            Input buffer size in bytes, per channel.
 */
[[nodiscard]] constexpr std::size_t
compute_native_dsd_input_bytes_per_channel(std::size_t num_frames) noexcept {
    return num_frames * 4u;
}

/**
 * Returns the total output buffer size in bytes for `num_frames` native DSD
 * stereo frames.
 *
 * Output layout: [L_slot_4B][R_slot_4B] per frame = 8 bytes/frame.
 * This is identical to `compute_dop_output_bytes()` — both DoP and Native DSD
 * produce 8 output bytes per stereo frame, so ISO transfer buffers and ring
 * buffers can be sized uniformly.
 *
 * @param num_frames  Number of native DSD stereo output frames.
 * @return            Output buffer size in bytes (interleaved L/R DSD slots).
 */
[[nodiscard]] constexpr std::size_t
compute_native_dsd_output_bytes(std::size_t num_frames) noexcept {
    return num_frames * 8u;
}

// ─────────────────────────────────────────────────────────────────────────────
// Planar DSD input → Native DSD_U32LE output
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Pack planar MSBF DSD bytes into a stereo-interleaved DSD_U32LE stream.
 *
 * ### Input format (planar)
 *
 * `dsd_left` and `dsd_right` are independent byte arrays with `4 × num_frames`
 * bytes each.  Consecutive groups of 4 bytes form one 32-bit DSD word:
 *
 *   dsd_left[4i+0]  → L DSD byte 0 for frame i  (DSD bits [31:24], MSBF)
 *   dsd_left[4i+1]  → L DSD byte 1 for frame i  (DSD bits [23:16])
 *   dsd_left[4i+2]  → L DSD byte 2 for frame i  (DSD bits [15: 8])
 *   dsd_left[4i+3]  → L DSD byte 3 for frame i  (DSD bits  [7: 0])
 *
 * The same indexing applies to `dsd_right`.
 *
 * ### Output format (interleaved DSD_U32LE)
 *
 * For each frame i, `dsd_out` receives 8 bytes:
 *
 *   dsd_out[8i+0 … 8i+3]  ← Left  channel DSD word (MSBF, LE byte order)
 *   dsd_out[8i+4 … 8i+7]  ← Right channel DSD word (MSBF, LE byte order)
 *
 * No bit-reversal, no marker bytes, no zero-padding — every output bit is DSD data.
 *
 * ### Performance
 *
 * On ARM64 with -O3, the inner loop body compiles to a pair of LDP + STP
 * instructions, loading 4 bytes per channel and storing 8 bytes per frame in
 * ~2 cycles.  At DSD128 / 1 ms transfer (≈177 frames): ~354 ns — negligible.
 *
 * ### Preconditions (checked only in debug builds)
 *
 *   • `dsd_left`  is non-null when `num_frames > 0`
 *   • `dsd_right` is non-null when `num_frames > 0`
 *   • `dsd_out`   is non-null when `num_frames > 0`
 *   • `dsd_left`  has ≥ `4 × num_frames` bytes
 *   • `dsd_right` has ≥ `4 × num_frames` bytes
 *   • `dsd_out`   has ≥ `8 × num_frames` bytes
 *
 * @param dsd_left   Planar left-channel  DSD bytes (MSBF, from FFmpeg).
 * @param dsd_right  Planar right-channel DSD bytes (MSBF, from FFmpeg).
 * @param dsd_out    Destination for interleaved DSD_U32LE stereo frames.
 * @param num_frames Number of native DSD stereo frames to encode.
 */
void format_native_dsd_stereo_msbf(
        const uint8_t *__restrict__ dsd_left,
        const uint8_t *__restrict__ dsd_right,
        uint8_t       *__restrict__ dsd_out,
        std::size_t                  num_frames) noexcept;

/**
 * Pack planar LSBF-converted DSD bytes into a stereo-interleaved DSD_U32LE stream.
 *
 * Identical to `format_native_dsd_stereo_msbf()` except that each input DSD
 * byte is bit-reversed before packing, converting the MSBF-ordered FFmpeg output
 * into the LSBF byte ordering required by some legacy DAC firmware.
 *
 * ### When to use
 *
 * Use this variant only when the connected DAC's UAC2 descriptor or vendor
 * documentation explicitly states that bit 0 of each DSD byte is the first DSD
 * clock pulse.  Most modern DACs (FiiO, RME, Topping) expect MSBF and should
 * use `format_native_dsd_stereo_msbf()` instead.
 *
 * ### Preconditions
 *
 * Identical to `format_native_dsd_stereo_msbf()`.
 *
 * @param dsd_left   Planar left-channel  DSD bytes (MSBF input; will be reversed).
 * @param dsd_right  Planar right-channel DSD bytes (MSBF input; will be reversed).
 * @param dsd_out    Destination for interleaved DSD_U32LE stereo frames (LSBF).
 * @param num_frames Number of native DSD stereo frames to encode.
 */
void format_native_dsd_stereo_lsbf(
        const uint8_t *__restrict__ dsd_left,
        const uint8_t *__restrict__ dsd_right,
        uint8_t       *__restrict__ dsd_out,
        std::size_t                  num_frames) noexcept;

// ─────────────────────────────────────────────────────────────────────────────
// Interleaved DSD input → Native DSD_U32LE output
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Pack interleaved MSBF DSD bytes into a stereo-interleaved DSD_U32LE stream.
 *
 * ### Input format (interleaved by byte per channel)
 *
 * `dsd_interleaved` is a single buffer where bytes alternate per channel in
 * groups of 2 bytes per channel per DSD word position:
 *
 *   Byte 8i+0 → L DSD byte 0 for frame i  (DSD bits [31:24], left)
 *   Byte 8i+1 → R DSD byte 0 for frame i  (DSD bits [31:24], right)
 *   Byte 8i+2 → L DSD byte 1 for frame i  (DSD bits [23:16], left)
 *   Byte 8i+3 → R DSD byte 1 for frame i  (DSD bits [23:16], right)
 *   Byte 8i+4 → L DSD byte 2 for frame i  (DSD bits [15: 8], left)
 *   Byte 8i+5 → R DSD byte 2 for frame i  (DSD bits [15: 8], right)
 *   Byte 8i+6 → L DSD byte 3 for frame i  (DSD bits  [7: 0], left)
 *   Byte 8i+7 → R DSD byte 3 for frame i  (DSD bits  [7: 0], right)
 *
 * This layout matches DSDIFF / DSF files decoded in non-planar mode by FFmpeg.
 *
 * ### Output format
 *
 * Identical to `format_native_dsd_stereo_msbf()` — interleaved DSD_U32LE.
 *
 * @param dsd_interleaved  Interleaved DSD bytes (MSBF), 8 bytes per output frame.
 * @param dsd_out          Destination for interleaved DSD_U32LE stereo frames.
 * @param num_frames       Number of native DSD stereo frames to encode.
 */
void format_native_dsd_from_interleaved_msbf(
        const uint8_t *__restrict__ dsd_interleaved,
        uint8_t       *__restrict__ dsd_out,
        std::size_t                  num_frames) noexcept;

/**
 * Pack interleaved MSBF DSD bytes into a DSD_U32LE stream with LSBF byte ordering.
 *
 * Identical to `format_native_dsd_from_interleaved_msbf()` except that each
 * DSD byte is bit-reversed via `bit_reverse_byte()` before packing.
 *
 * @param dsd_interleaved  Interleaved DSD bytes (MSBF input), 8 bytes per frame.
 * @param dsd_out          Destination for interleaved DSD_U32LE stereo frames (LSBF).
 * @param num_frames       Number of native DSD stereo frames to encode.
 */
void format_native_dsd_from_interleaved_lsbf(
        const uint8_t *__restrict__ dsd_interleaved,
        uint8_t       *__restrict__ dsd_out,
        std::size_t                  num_frames) noexcept;

// ─────────────────────────────────────────────────────────────────────────────
// Zero-overhead formatter dispatch — functor types and DsdFrameFormatter variant
// ─────────────────────────────────────────────────────────────────────────────
//
// Three functor types share an identical operator() signature:
//
//   void operator()(const uint8_t* left, const uint8_t* right,
//                   uint8_t* out, std::size_t num_frames) noexcept;
//
// Uniform guarantee across all three:
//   • Writes exactly (8 × num_frames) bytes to `out`.
//   • `out` must have at least (8 × num_frames) bytes of space.
//
// Per-type input consumption (bytes from left/right per frame):
//   DopFormatterFunctor      → 2 bytes / channel / frame  (2 DSD bytes → 8-byte DoP word)
//   NativeDsdMsbfFormatter   → 4 bytes / channel / frame  (4 DSD bytes → 8-byte slot pair)
//   NativeDsdLsbfFormatter   → 4 bytes / channel / frame  (bit-reversed; same output size)
//
// Dispatching via std::visit() on a DsdFrameFormatter produces a jump-table or
// 2-comparison chain of DIRECT calls, not an indirect branch — see file header.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Stateful DoP 1.1 formatter functor that chains the marker byte across calls.
 *
 * Wraps `format_dop_stereo()` from dop_formatter.h as a callable compatible
 * with the `DsdFrameFormatter` variant, enabling zero-overhead runtime dispatch
 * between DoP and Native DSD modes within the isochronous callback.
 *
 * ### State
 * `next_marker` is the only mutable field — it carries the 0x05/0xFA alternation
 * across successive operator() invocations for the lifetime of a playback session.
 * Reset to `kDopMarkerA` when a new playback session begins.
 *
 * ### Input / output contract
 * - `left` / `right` must each have ≥ `2 × num_frames` valid DSD bytes.
 * - Writes exactly `8 × num_frames` bytes to `out`.
 *
 * @property next_marker  The DoP marker byte to use at the start of the next call.
 *                        Initialised to `kDopMarkerA` (0x05).
 *
 * @see format_dop_stereo
 * @see DsdFrameFormatter
 */
struct DopFormatterFunctor {
    /**
     * The DoP marker byte to use at the start of the next `operator()` call.
     *
     * Advances between `kDopMarkerA` (0x05) and `kDopMarkerB` (0xFA) each frame.
     * Stored here so marker state persists across ring-buffer segment boundaries.
     */
    uint8_t next_marker = kDopMarkerA;

    /**
     * Format `num_frames` DoP stereo frames from planar MSBF DSD input.
     *
     * Calls `format_dop_stereo()` and chains its return value back into
     * `next_marker` for correct marker alternation on the next invocation.
     *
     * @param left       Planar left-channel  DSD bytes (MSBF); ≥ 2 × num_frames bytes.
     * @param right      Planar right-channel DSD bytes (MSBF); ≥ 2 × num_frames bytes.
     * @param out        Destination buffer; ≥ 8 × num_frames bytes.
     * @param num_frames Number of DoP stereo frames to encode.
     */
    void operator()(const uint8_t *__restrict__ left,
                    const uint8_t *__restrict__ right,
                    uint8_t       *__restrict__ out,
                    std::size_t                  num_frames) noexcept
    {
        next_marker = format_dop_stereo(left, right, out, num_frames, next_marker);
    }
};

/**
 * Stateless Native DSD formatter functor — MSBF byte ordering, 32-bit slots.
 *
 * Delegates to `format_native_dsd_stereo_msbf()`.  DSD bytes from FFmpeg
 * (DSD_MSBF_PLANAR) are forwarded verbatim — no bit-reversal, no marker bytes,
 * no zero-padding overhead.
 *
 * ### Input / output contract
 * - `left` / `right` must each have ≥ `4 × num_frames` valid DSD bytes.
 * - Writes exactly `8 × num_frames` bytes to `out`.
 *
 * Compatible with: FiiO K9 Pro / Q7 / BTR7, RME ADI-2 DAC FS / ADI-2 Pro,
 *                  Topping DX3 Pro+ / DX5, Matrix Audio X-SABRE 3.
 *
 * @see format_native_dsd_stereo_msbf
 * @see DsdFrameFormatter
 */
struct NativeDsdMsbfFormatter {
    /**
     * Format `num_frames` native DSD stereo frames (32-bit, MSBF) from planar input.
     *
     * @param left       Planar left-channel  DSD bytes (MSBF); ≥ 4 × num_frames bytes.
     * @param right      Planar right-channel DSD bytes (MSBF); ≥ 4 × num_frames bytes.
     * @param out        Destination buffer; ≥ 8 × num_frames bytes.
     * @param num_frames Number of native DSD stereo 32-bit frames to encode.
     */
    void operator()(const uint8_t *__restrict__ left,
                    const uint8_t *__restrict__ right,
                    uint8_t       *__restrict__ out,
                    std::size_t                  num_frames) const noexcept
    {
        format_native_dsd_stereo_msbf(left, right, out, num_frames);
    }
};

/**
 * Stateless Native DSD formatter functor — LSBF byte ordering, 32-bit slots.
 *
 * Delegates to `format_native_dsd_stereo_lsbf()`.  Each DSD byte from FFmpeg
 * (MSBF) is bit-reversed via `bit_reverse_byte()` before packing.
 *
 * ### Input / output contract
 * - `left` / `right` must each have ≥ `4 × num_frames` valid DSD bytes.
 * - Writes exactly `8 × num_frames` bytes to `out`.
 *
 * ### When to use
 * Only when the DAC's documentation explicitly requires LSBF within each DSD
 * byte.  Check the UAC2 descriptor or vendor SDK before enabling this variant.
 *
 * @see format_native_dsd_stereo_lsbf
 * @see DsdFrameFormatter
 */
struct NativeDsdLsbfFormatter {
    /**
     * Format `num_frames` native DSD stereo frames (32-bit, LSBF) from planar input.
     *
     * @param left       Planar left-channel  DSD bytes (MSBF input); ≥ 4 × num_frames bytes.
     * @param right      Planar right-channel DSD bytes (MSBF input); ≥ 4 × num_frames bytes.
     * @param out        Destination buffer; ≥ 8 × num_frames bytes.
     * @param num_frames Number of native DSD stereo 32-bit frames to encode.
     */
    void operator()(const uint8_t *__restrict__ left,
                    const uint8_t *__restrict__ right,
                    uint8_t       *__restrict__ out,
                    std::size_t                  num_frames) const noexcept
    {
        format_native_dsd_stereo_lsbf(left, right, out, num_frames);
    }
};

// ─────────────────────────────────────────────────────────────────────────────
// DsdFrameFormatter — unified variant for the isochronous callback hot path
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Unified DSD output formatter for the USB isochronous callback hot path.
 *
 * Holds exactly one active formatter — either DoP, Native MSBF, or Native LSBF —
 * in a `std::variant` that enables zero-overhead dispatch through `std::visit`.
 *
 * ### Why std::variant outperforms a function pointer here
 *
 *   Function pointer:   ldr  x8, [fp_ptr]  ; load pointer
 *                       blr  x8            ; INDIRECT branch — always mispredicted first call
 *
 *   std::variant visit: ldr  w8, [var+idx] ; load 1-byte discriminant
 *                       cbz  w8, #DoP      ; branch → DoP direct call (inlinable)
 *                       cmp  w8, #1
 *                       b.eq #NativeMsbf   ; branch → Native MSBF direct call (inlinable)
 *                       b    #NativeLsbf   ; fallthrough → Native LSBF direct call
 *
 * After the second isochronous callback (where the same branch is taken every time),
 * the branch predictor correctly predicts the common path at near-zero cost.
 * All three target sites are *direct* calls — the compiler can inline them.
 *
 * ### Usage
 *
 * @code
 *   // Allocate at session start — once per DAC connection:
 *   DsdFrameFormatter fmt = make_dsd_frame_formatter(
 *       summary.supports_native_dsd,
 *       summary.native_dsd_transport,
 *       preferred_bit_order);
 *
 *   // Inside the isochronous completion callback (called every USB µframe):
 *   dispatch_dsd_frames(fmt, dsd_left, dsd_right, iso_buf, num_frames);
 * @endcode
 *
 * @see DopFormatterFunctor
 * @see NativeDsdMsbfFormatter
 * @see NativeDsdLsbfFormatter
 * @see make_dsd_frame_formatter
 * @see dispatch_dsd_frames
 */
using DsdFrameFormatter = std::variant<
    DopFormatterFunctor,
    NativeDsdMsbfFormatter,
    NativeDsdLsbfFormatter
>;

// ─────────────────────────────────────────────────────────────────────────────
// Factory and dispatch helpers
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Construct the correct `DsdFrameFormatter` variant for a detected DAC capability.
 *
 * Routes based on the detector output from `uac2_detect_native_dsd()`:
 *
 *   `use_native_dsd == true`  and `bit_order == Msbf`  → `NativeDsdMsbfFormatter`
 *   `use_native_dsd == true`  and `bit_order == Lsbf`  → `NativeDsdLsbfFormatter`
 *   `use_native_dsd == false` (DoP fallback)           → `DopFormatterFunctor`
 *
 * ### DoP marker initialisation
 *
 * The `DopFormatterFunctor` is constructed with `next_marker == kDopMarkerA`
 * (0x05).  This is correct for a fresh playback session.  If the formatter
 * must resume mid-stream (e.g., after a ring-buffer underrun recovery), obtain
 * the last emitted marker from the existing `DopFormatterFunctor` before
 * creating a new one and re-inject it.
 *
 * @param use_native_dsd  `true` to select a native DSD path; `false` for DoP.
 * @param bit_order       Which bit order the DAC expects within each DSD byte.
 *                        Ignored when `use_native_dsd == false`.
 * @return                Fully constructed `DsdFrameFormatter` ready for the
 *                        isochronous callback.
 *
 * @see uac2_detect_native_dsd
 * @see DsdFrameFormatter
 */
[[nodiscard]] inline DsdFrameFormatter make_dsd_frame_formatter(
        bool             use_native_dsd,
        NativeDsdBitOrder bit_order = NativeDsdBitOrder::Msbf) noexcept
{
    if (!use_native_dsd) {
        return DopFormatterFunctor{};
    }
    if (bit_order == NativeDsdBitOrder::Msbf) {
        return NativeDsdMsbfFormatter{};
    }
    return NativeDsdLsbfFormatter{};
}

/**
 * Dispatch a DSD format call through the active formatter in the variant.
 *
 * This is the only call site needed in the isochronous completion callback.
 * It is a thin wrapper over `std::visit` that keeps the callback readable by
 * hiding the visitor boilerplate.
 *
 * ### Output guarantee
 *
 * Exactly `8 × num_frames` bytes are written to `out` regardless of which
 * formatter variant is active.
 *
 * ### Thread safety
 *
 * The variant and its active formatter are NOT shared between threads.
 * The formatter lives in the `UsbDriverContext` and is written only by the
 * JNI init thread; the isochronous callback (consumer) reads it.  Callers
 * must ensure the JNI init thread has finished writing before the first
 * callback fires.
 *
 * @param formatter  Active formatter variant (from `make_dsd_frame_formatter()`).
 * @param left       Planar left-channel  DSD bytes; size per formatter contract.
 * @param right      Planar right-channel DSD bytes; size per formatter contract.
 * @param out        Destination ISO OUT transfer buffer; ≥ 8 × num_frames bytes.
 * @param num_frames Number of stereo output frames.  Writes 8 × num_frames bytes.
 *
 * @see DsdFrameFormatter
 * @see make_dsd_frame_formatter
 */
inline void dispatch_dsd_frames(
        DsdFrameFormatter           &formatter,
        const uint8_t *__restrict__  left,
        const uint8_t *__restrict__  right,
        uint8_t       *__restrict__  out,
        std::size_t                  num_frames) noexcept
{
    // std::visit emits a 3-entry index-gated dispatch.  All three operator()
    // overloads are direct calls — inlining is possible at -O3 and the branch
    // predictor learns the active variant after 1–2 callback invocations.
    std::visit([&](auto &fmt) noexcept {
        fmt(left, right, out, num_frames);
    }, formatter);
}

