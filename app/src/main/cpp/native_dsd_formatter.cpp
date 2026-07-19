// ─────────────────────────────────────────────────────────────────────────────
// native_dsd_formatter.cpp
//
// Step 12 — Native DSD (DSD_U32LE) formatter implementation.
//
// Implements the four free functions declared in native_dsd_formatter.h:
//
//   format_native_dsd_stereo_msbf          — planar MSBF → DSD_U32LE
//   format_native_dsd_stereo_lsbf          — planar MSBF → DSD_U32LE (bit-reversed)
//   format_native_dsd_from_interleaved_msbf — interleaved MSBF → DSD_U32LE
//   format_native_dsd_from_interleaved_lsbf — interleaved MSBF → DSD_U32LE (bit-reversed)
//
// The functor types (DopFormatterFunctor, NativeDsdMsbfFormatter,
// NativeDsdLsbfFormatter) and their operator() bodies are header-inline:
// they delegate directly to these free functions or to format_dop_stereo().
//
// See native_dsd_formatter.h for:
//   • DSD_U32LE frame layout diagrams
//   • MSBF / LSBF bit-ordering rationale
//   • Buffer size contract
//   • DsdFrameFormatter variant dispatch explanation
// ─────────────────────────────────────────────────────────────────────────────

#include "native_dsd_formatter.h"

#include <cassert>
#include <cstring>   // __builtin_memcpy

// ─────────────────────────────────────────────────────────────────────────────
// write_le32 — portable explicit little-endian 32-bit store
// ─────────────────────────────────────────────────────────────────────────────
//
// Mirrors the write_le32 helper in dop_formatter.cpp.  Declared static here so
// both translation units inline their own copy without a link-visible symbol;
// the compiler folds identical code across TUs with -flto at release.

/**
 * Write a 32-bit value to an unaligned byte address in little-endian order.
 *
 * Identical semantics to the write_le32 in dop_formatter.cpp — see that file
 * for the full unaligned-store, aliasing, and compilation rationale.
 *
 * On Android LE hosts (arm64-v8a, armeabi-v7a, x86_64) this compiles to a
 * single unaligned 32-bit store instruction (STR Wn / MOV DWORD PTR) at -O2+.
 *
 * @param dst  Destination byte pointer (unaligned, non-null).
 * @param val  32-bit value to write in little-endian order.
 */
[[gnu::always_inline]]
static inline void write_le32(uint8_t *__restrict__ dst,
                               uint32_t              val) noexcept
{
#if defined(__BYTE_ORDER__) && (__BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__)
    __builtin_memcpy(dst, &val, sizeof(uint32_t));
#else
    dst[0] = static_cast<uint8_t>(val);
    dst[1] = static_cast<uint8_t>(val >>  8u);
    dst[2] = static_cast<uint8_t>(val >> 16u);
    dst[3] = static_cast<uint8_t>(val >> 24u);
#endif
}

// ─────────────────────────────────────────────────────────────────────────────
// format_native_dsd_stereo_msbf — planar MSBF, no bit transformation
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Pack planar MSBF DSD bytes into a stereo-interleaved DSD_U32LE stream.
 *
 * See native_dsd_formatter.h for the full API contract, input/output layout
 * diagrams, and precondition list.
 *
 * ### Per-iteration work (hot path)
 *
 * On little-endian Android ABIs the MSBF conversion is register-order neutral:
 *   - Left word:  l[0] → bits [7:0], l[1] → bits [15:8], l[2] → bits [23:16], l[3] → bits [31:24]
 *   - Right word: identical layout for r[0..3]
 *
 * At -O3 the compiler represents the 4-byte load as a single 32-bit LDR and
 * the write_le32 as a single 32-bit STR, folding the LE "swizzle" away because
 * it is a no-op on LE hosts:
 *
 *   ARM64:  LDP  W0, W1, [src]    ; load 4 bytes L and 4 bytes R simultaneously
 *           STP  W0, W1, [out]    ; store 8 bytes in one instruction
 *
 * This is the fastest possible path — just a 4+4 byte memory copy with stride,
 * clocking ~1 cycle per stereo frame at L1 hit rate (Cortex-A75).
 *
 * ### Why write_le32 instead of memcpy
 *
 * On big-endian hypothetical hosts, loading 4 bytes as a uint32_t with LDR and
 * storing via write_le32 ensures correct LE output byte order.  On LE hosts
 * (all Android ABIs) both paths compile identically.  The explicit construction
 * of `word_l` makes the intended layout self-documenting for byte-order reviews.
 *
 * @param dsd_left   Planar left-channel  DSD bytes (MSBF).
 * @param dsd_right  Planar right-channel DSD bytes (MSBF).
 * @param dsd_out    Destination for interleaved DSD_U32LE stereo frames.
 * @param num_frames Native DSD 32-bit stereo frames to encode.
 */
void format_native_dsd_stereo_msbf(
        const uint8_t *__restrict__ dsd_left,
        const uint8_t *__restrict__ dsd_right,
        uint8_t       *__restrict__ dsd_out,
        std::size_t                  num_frames) noexcept
{
    assert((num_frames == 0 || dsd_left  != nullptr) && "dsd_left is null");
    assert((num_frames == 0 || dsd_right != nullptr) && "dsd_right is null");
    assert((num_frames == 0 || dsd_out   != nullptr) && "dsd_out is null");

    const uint8_t *l   = dsd_left;
    const uint8_t *r   = dsd_right;
    uint8_t       *out = dsd_out;

    for (std::size_t i = 0; i < num_frames; ++i, l += 4, r += 4, out += 8) {
        // ── Build Left channel 32-bit DSD slot ────────────────────────────────
        //
        // DSD_U32LE MSBF layout (LE memory at out[0..3] after write_le32):
        //
        //   out[0] = l[0]  → DSD bits [31:24] — first  DSD byte  (earliest in time)
        //   out[1] = l[1]  → DSD bits [23:16] — second DSD byte
        //   out[2] = l[2]  → DSD bits [15: 8] — third  DSD byte
        //   out[3] = l[3]  → DSD bits  [7: 0] — fourth DSD byte (latest  in time)
        //
        // On LE hosts this is a straight-through copy: the uint32_t value below
        // stores l[0] at the lowest byte address, l[3] at the highest, which is
        // exactly chronological byte order (first DSD bit in time at offset 0).
        //
        // No marker bytes, no zero-padding — all 32 output bits are DSD data.
        const uint32_t word_l =
            static_cast<uint32_t>(l[0])         |    // bits [ 7: 0]
            (static_cast<uint32_t>(l[1]) <<  8u) |   // bits [15: 8]
            (static_cast<uint32_t>(l[2]) << 16u) |   // bits [23:16]
            (static_cast<uint32_t>(l[3]) << 24u);    // bits [31:24]

        // ── Build Right channel 32-bit DSD slot (identical layout) ────────────
        const uint32_t word_r =
            static_cast<uint32_t>(r[0])         |
            (static_cast<uint32_t>(r[1]) <<  8u) |
            (static_cast<uint32_t>(r[2]) << 16u) |
            (static_cast<uint32_t>(r[3]) << 24u);

        // ── Write both slots to the output buffer ──────────────────────────────
        //
        // Interleaved stereo layout at out[0..7]:
        //   out[0..3]  = word_l  (Left  DSD_U32LE slot)
        //   out[4..7]  = word_r  (Right DSD_U32LE slot)
        //
        // The two adjacent write_le32 calls are candidates for STP fusion on ARM64
        // (two 32-bit stores → one 64-bit store pair), cutting memory transactions
        // per frame in half.
        write_le32(out,     word_l);
        write_le32(out + 4, word_r);
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// format_native_dsd_stereo_lsbf — planar MSBF input, LSBF output
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Pack planar MSBF DSD bytes into a stereo-interleaved DSD_U32LE stream with
 * LSBF byte ordering.
 *
 * See native_dsd_formatter.h for the full API contract.
 *
 * ### Per-iteration work
 *
 *   4 × RBIT+LSR  (bit_reverse_byte for each DSD byte)
 *   4 × ORR+LSL   (assemble uint32_t)
 *   2 × STR       (write_le32 via write_le32)
 *
 * bit_reverse_byte() compiles to RBIT+LSR on ARM64 (~2 cycles per byte).
 * For DSD128 / 177 frames / 1 ms: 177 × 4 × 2 ≈ 1,416 RBIT cycles ≈ 472 ns
 * at 3 GHz — still negligible on the isochronous callback timeline.
 *
 * @param dsd_left   Planar left-channel  DSD bytes (MSBF input).
 * @param dsd_right  Planar right-channel DSD bytes (MSBF input).
 * @param dsd_out    Destination for interleaved DSD_U32LE stereo frames (LSBF).
 * @param num_frames Native DSD 32-bit stereo frames to encode.
 */
void format_native_dsd_stereo_lsbf(
        const uint8_t *__restrict__ dsd_left,
        const uint8_t *__restrict__ dsd_right,
        uint8_t       *__restrict__ dsd_out,
        std::size_t                  num_frames) noexcept
{
    assert((num_frames == 0 || dsd_left  != nullptr) && "dsd_left is null");
    assert((num_frames == 0 || dsd_right != nullptr) && "dsd_right is null");
    assert((num_frames == 0 || dsd_out   != nullptr) && "dsd_out is null");

    const uint8_t *l   = dsd_left;
    const uint8_t *r   = dsd_right;
    uint8_t       *out = dsd_out;

    for (std::size_t i = 0; i < num_frames; ++i, l += 4, r += 4, out += 8) {
        // ── Bit-reverse each DSD byte for LSBF ordering ───────────────────────
        //
        // MSBF input: bit 7 = first DSD clock pulse in time.
        // LSBF output: bit 0 = first DSD clock pulse in time.
        // bit_reverse_byte() swaps the two orderings via 3 XOR operations.
        // ARM64 compiles each call to: RBIT Wn, Wn; LSR Wn, Wn, #24 (2 cycles).
        //
        // The reversed bytes are packed into uint32_t words in the same
        // chronological byte order as the MSBF path — only the bit ordering
        // within each byte changes.
        const uint32_t word_l =
            static_cast<uint32_t>(bit_reverse_byte(l[0]))         |
            (static_cast<uint32_t>(bit_reverse_byte(l[1])) <<  8u) |
            (static_cast<uint32_t>(bit_reverse_byte(l[2])) << 16u) |
            (static_cast<uint32_t>(bit_reverse_byte(l[3])) << 24u);

        const uint32_t word_r =
            static_cast<uint32_t>(bit_reverse_byte(r[0]))         |
            (static_cast<uint32_t>(bit_reverse_byte(r[1])) <<  8u) |
            (static_cast<uint32_t>(bit_reverse_byte(r[2])) << 16u) |
            (static_cast<uint32_t>(bit_reverse_byte(r[3])) << 24u);

        write_le32(out,     word_l);
        write_le32(out + 4, word_r);
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// format_native_dsd_from_interleaved_msbf — interleaved MSBF, no transformation
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Pack interleaved MSBF DSD bytes into a stereo-interleaved DSD_U32LE stream.
 *
 * See native_dsd_formatter.h for the full API contract and interleaved input
 * layout (8 source bytes per output frame, alternating L/R per DSD byte position).
 *
 * ### Input stride pattern for frame i (src pointer base = dsd_interleaved + 8i)
 *
 *   src[0]  → L DSD byte 0  →  bits [ 7: 0] of word_l  (DSD bits [31:24])
 *   src[1]  → R DSD byte 0  →  bits [ 7: 0] of word_r
 *   src[2]  → L DSD byte 1  →  bits [15: 8] of word_l  (DSD bits [23:16])
 *   src[3]  → R DSD byte 1  →  bits [15: 8] of word_r
 *   src[4]  → L DSD byte 2  →  bits [23:16] of word_l  (DSD bits [15: 8])
 *   src[5]  → R DSD byte 2  →  bits [23:16] of word_r
 *   src[6]  → L DSD byte 3  →  bits [31:24] of word_l  (DSD bits  [7: 0])
 *   src[7]  → R DSD byte 3  →  bits [31:24] of word_r
 *
 * The de-interleaving scatter is slightly more cache-unfriendly than the planar
 * variant (non-contiguous strides per channel), but for typical USB audio buffer
 * sizes (≤ 4 KB) both fit comfortably in L1 cache.
 *
 * @param dsd_interleaved  Interleaved DSD bytes (MSBF), 8 bytes per output frame.
 * @param dsd_out          Destination for interleaved DSD_U32LE stereo frames.
 * @param num_frames       Native DSD 32-bit stereo frames to encode.
 */
void format_native_dsd_from_interleaved_msbf(
        const uint8_t *__restrict__ dsd_interleaved,
        uint8_t       *__restrict__ dsd_out,
        std::size_t                  num_frames) noexcept
{
    assert((num_frames == 0 || dsd_interleaved != nullptr) && "dsd_interleaved is null");
    assert((num_frames == 0 || dsd_out         != nullptr) && "dsd_out is null");

    const uint8_t *src = dsd_interleaved;
    uint8_t       *out = dsd_out;

    for (std::size_t i = 0; i < num_frames; ++i, src += 8, out += 8) {
        // ── De-interleave and pack Left channel word ──────────────────────────
        //
        // L bytes are at even offsets (0, 2, 4, 6) within the 8-byte input block.
        // R bytes are at odd  offsets (1, 3, 5, 7).
        // Both are assembled into chronological-order DSD_U32LE words.
        const uint32_t word_l =
            static_cast<uint32_t>(src[0])         |   // L DSD byte 0
            (static_cast<uint32_t>(src[2]) <<  8u) |  // L DSD byte 1
            (static_cast<uint32_t>(src[4]) << 16u) |  // L DSD byte 2
            (static_cast<uint32_t>(src[6]) << 24u);   // L DSD byte 3

        // ── De-interleave and pack Right channel word ─────────────────────────
        const uint32_t word_r =
            static_cast<uint32_t>(src[1])         |   // R DSD byte 0
            (static_cast<uint32_t>(src[3]) <<  8u) |  // R DSD byte 1
            (static_cast<uint32_t>(src[5]) << 16u) |  // R DSD byte 2
            (static_cast<uint32_t>(src[7]) << 24u);   // R DSD byte 3

        write_le32(out,     word_l);
        write_le32(out + 4, word_r);
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// format_native_dsd_from_interleaved_lsbf — interleaved MSBF input, LSBF output
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Pack interleaved MSBF DSD bytes into a DSD_U32LE stream with LSBF byte ordering.
 *
 * See native_dsd_formatter.h for the full API contract.
 *
 * Combines the de-interleaving scatter of
 * `format_native_dsd_from_interleaved_msbf()` with the per-byte bit-reversal of
 * `format_native_dsd_stereo_lsbf()`.
 *
 * @param dsd_interleaved  Interleaved DSD bytes (MSBF input), 8 bytes per frame.
 * @param dsd_out          Destination for interleaved DSD_U32LE stereo frames (LSBF).
 * @param num_frames       Native DSD 32-bit stereo frames to encode.
 */
void format_native_dsd_from_interleaved_lsbf(
        const uint8_t *__restrict__ dsd_interleaved,
        uint8_t       *__restrict__ dsd_out,
        std::size_t                  num_frames) noexcept
{
    assert((num_frames == 0 || dsd_interleaved != nullptr) && "dsd_interleaved is null");
    assert((num_frames == 0 || dsd_out         != nullptr) && "dsd_out is null");

    const uint8_t *src = dsd_interleaved;
    uint8_t       *out = dsd_out;

    for (std::size_t i = 0; i < num_frames; ++i, src += 8, out += 8) {
        // De-interleave (L at even offsets, R at odd offsets) AND bit-reverse
        // each byte to convert from MSBF to LSBF chronological ordering.
        const uint32_t word_l =
            static_cast<uint32_t>(bit_reverse_byte(src[0]))         |
            (static_cast<uint32_t>(bit_reverse_byte(src[2])) <<  8u) |
            (static_cast<uint32_t>(bit_reverse_byte(src[4])) << 16u) |
            (static_cast<uint32_t>(bit_reverse_byte(src[6])) << 24u);

        const uint32_t word_r =
            static_cast<uint32_t>(bit_reverse_byte(src[1]))         |
            (static_cast<uint32_t>(bit_reverse_byte(src[3])) <<  8u) |
            (static_cast<uint32_t>(bit_reverse_byte(src[5])) << 16u) |
            (static_cast<uint32_t>(bit_reverse_byte(src[7])) << 24u);

        write_le32(out,     word_l);
        write_le32(out + 4, word_r);
    }
}

