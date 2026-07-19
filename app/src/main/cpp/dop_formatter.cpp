// ─────────────────────────────────────────────────────────────────────────────
// dop_formatter.cpp
//
// DoP 1.1 framing implementation.
//
// See dop_formatter.h for the full specification, bit-layout diagrams,
// buffer sizing contract, and marker-chaining usage pattern.
// ─────────────────────────────────────────────────────────────────────────────

#include "dop_formatter.h"

#include <cassert>
#include <cstring>    // __builtin_memcpy (compiler intrinsic)

// ─────────────────────────────────────────────────────────────────────────────
// write_le32 — portable explicit little-endian 32-bit store helper
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Write a 32-bit value to an unaligned byte address in little-endian order.
 *
 * ### Why this helper exists
 *
 * The DoP output buffer is a raw `uint8_t*` stream; its individual PCM word
 * positions are NOT guaranteed to be 4-byte aligned (the buffer starts at
 * whatever address the isochronous transfer pool allocated, and words after
 * the first may be at any byte offset).
 *
 * A direct `*reinterpret_cast<uint32_t*>(dst) = val` would invoke undefined
 * behaviour on misaligned addresses on strict-alignment architectures (though
 * ARM64 in practice handles it, the compiler may still mis-optimise).
 *
 * ### Compilation result on Android targets
 *
 * On little-endian hosts (all Android ABIs: arm64-v8a, armeabi-v7a, x86_64),
 * the __builtin_memcpy path compiles with -O2+ to:
 *   ARM64:  STR  Wn, [Xm]        — single unaligned 32-bit store (1 cycle)
 *   x86-64: MOV  DWORD PTR [rdi] — single unaligned 32-bit store (1 cycle)
 *
 * The big-endian fallback generates four 8-bit stores + three shift instructions.
 * That path is never reached on any current Android device.
 *
 * ### Strict-aliasing safety
 *
 * `uint8_t` is defined as `unsigned char` on all C++ ABI implementations.
 * Under the C++ standard, `unsigned char*` may alias any object — so reading
 * and writing through a `uint8_t*` is the only fully defined way to perform
 * type-punning in C++ without `std::bit_cast` (C++20).
 * `__builtin_memcpy` copies the representation bytes without violating the
 * aliasing rules and without introducing a real function-call overhead.
 *
 * @param dst  Destination byte pointer (unaligned, non-null).
 * @param val  32-bit value to store in little-endian byte order.
 */
[[gnu::always_inline]]
static inline void write_le32(uint8_t *__restrict__ dst,
                               uint32_t             val) noexcept
{
#if defined(__BYTE_ORDER__) && (__BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__)
    // Fast path: host is little-endian (all Android ABIs).
    // __builtin_memcpy with a compile-time-constant size=4 is lowered by
    // Clang/GCC to a single unaligned 32-bit store — no actual memcpy call.
    __builtin_memcpy(dst, &val, sizeof(uint32_t));
#else
    // Portable fallback for hypothetical big-endian hosts.
    // Each byte is individually extracted and stored LSB-first.
    dst[0] = static_cast<uint8_t>(val);          // bits [ 7: 0]  → 0x00
    dst[1] = static_cast<uint8_t>(val >>  8u);   // bits [15: 8]  → DSD byte 1
    dst[2] = static_cast<uint8_t>(val >> 16u);   // bits [23:16]  → DSD byte 0
    dst[3] = static_cast<uint8_t>(val >> 24u);   // bits [31:24]  → marker
#endif
}

// ─────────────────────────────────────────────────────────────────────────────
// format_dop_stereo — planar DSD input
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Pack planar MSBF DSD bytes into a DoP 1.1 stereo interleaved PCM stream.
 *
 * See dop_formatter.h for the full API contract, buffer layout diagrams, and
 * marker-chaining usage pattern.
 *
 * ### Per-iteration work (hot path, all scalar)
 *
 *   Loads:    4 × LDR  (l[0], l[1], r[0], r[1])
 *   Shifts:   6 × LSL  (2 per word × 3 shift operations each)  — fused with ORR
 *   Stores:   2 × STR  via write_le32 (single unaligned 32-bit store per call)
 *   Toggle:   1 × EOR  (marker ^= kDopToggleMask)
 *   Advances: 3 × ADD  (l+=2, r+=2, out+=8)
 *   Total:  ~16 μops → ~4–5 cycles on Cortex-A75 with instruction-level
 *             parallelism and out-of-order execution.
 *
 * For 192 kHz / 8 packets / 1 ms transfer (192 DoP frames):
 *   192 × ~5 cycles @ 3 GHz ≈ 320 ns — negligible on the producer timeline.
 *
 * ### Why no SIMD intrinsics
 *
 * The per-frame output is only 8 bytes.  The dominant cost at this scale is
 * memory bandwidth (loads + stores), which NEON SIMD would also hit; the
 * auto-vectoriser with -O3 can widen to 128-bit NEON vectors if the loop body
 * remains free of loop-carried dependencies beyond the marker toggle.
 *
 * Eliminating the marker loop-carried dependency (by pre-computing both phases
 * and using a parity mask) would allow full vectorisation; that optimisation
 * is unnecessary at 192kHz and reserved for DSD512 rates (> 1.5 MHz).
 *
 * @param dsd_left       Planar left-channel  DSD bytes (MSBF).
 * @param dsd_right      Planar right-channel DSD bytes (MSBF).
 * @param pcm_out        Destination for interleaved 32-bit LE PCM.
 * @param num_dop_frames DoP stereo frames to encode.
 * @param initial_marker Starting marker; `kDopMarkerA` or `kDopMarkerB`.
 * @return               Next marker for chaining across ring-buffer pushes.
 */
uint8_t format_dop_stereo(
        const uint8_t *__restrict__ dsd_left,
        const uint8_t *__restrict__ dsd_right,
        uint8_t       *__restrict__ pcm_out,
        std::size_t                  num_dop_frames,
        uint8_t                      initial_marker) noexcept
{
    // ── Precondition assertions (debug builds only) ───────────────────────────
    assert((num_dop_frames == 0 || dsd_left  != nullptr) && "dsd_left is null");
    assert((num_dop_frames == 0 || dsd_right != nullptr) && "dsd_right is null");
    assert((num_dop_frames == 0 || pcm_out   != nullptr) && "pcm_out is null");
    assert((initial_marker == kDopMarkerA || initial_marker == kDopMarkerB) &&
           "initial_marker must be kDopMarkerA (0x05) or kDopMarkerB (0xFA)");
    // ─────────────────────────────────────────────────────────────────────────

    // Working pointers — advanced by the loop to avoid multiply-by-index.
    const uint8_t *l   = dsd_left;
    const uint8_t *r   = dsd_right;
    uint8_t       *out = pcm_out;

    // Current DoP marker byte; toggles every frame without a branch.
    uint8_t marker = initial_marker;

    for (std::size_t i = 0; i < num_dop_frames; ++i, l += 2, r += 2, out += 8) {
        // ── Build Left channel 32-bit word ────────────────────────────────────
        //
        // Mathematical (bit-field) layout of the uint32_t value:
        //
        //   Bits [31:24]  = marker        (DoP marker: 0x05 or 0xFA)
        //   Bits [23:16]  = l[0]          (first  DSD byte — higher DSD bits)
        //   Bits [15: 8]  = l[1]          (second DSD byte — lower  DSD bits)
        //   Bits [ 7: 0]  = 0x00          (mandatory zero-padding per DoP § 4)
        //
        // The LSB field is zero because the uint32_t is initialised with a shift
        // and OR of its three fields; the bits [7:0] are never written and thus
        // remain zero (default for uint32_t initialisation is implementation-
        // defined, BUT the expression (a << 24) | (b << 16) | (c << 8) naturally
        // leaves bits [7:0] as zero — no explicit `| 0x00u` needed).
        //
        // All casts to uint32_t on the operands prevent implicit integer
        // promotion from truncating values wider than 8 bits after the shift.
        const uint32_t word_l =
            (static_cast<uint32_t>(marker) << 24u) |
            (static_cast<uint32_t>(l[0])   << 16u) |
            (static_cast<uint32_t>(l[1])   <<  8u);

        // ── Build Right channel 32-bit word (same marker) ─────────────────────
        //
        // Both channels in the same stereo frame share the same marker byte.
        // This is mandated by DoP 1.1: the receiver uses the marker to detect
        // frame alignment; a marker mismatch between L and R would cause the
        // DAC's DoP detector to lose sync.
        const uint32_t word_r =
            (static_cast<uint32_t>(marker) << 24u) |
            (static_cast<uint32_t>(r[0])   << 16u) |
            (static_cast<uint32_t>(r[1])   <<  8u);

        // ── Write both words to the output buffer in little-endian order ──────
        //
        // Interleaved output layout per frame:
        //   out[0..3]  = word_l (Left  PCM word, LE)
        //   out[4..7]  = word_r (Right PCM word, LE)
        //
        // write_le32 on ARM64-LE compiles to a single STR Wn, [Xm] instruction.
        // The two writes can be fused into one STP (Store Pair) by the compiler
        // when both destinations and values are visible in the same basic block.
        write_le32(out,     word_l);
        write_le32(out + 4, word_r);

        // ── Toggle the DoP marker for the next frame ──────────────────────────
        //
        // kDopToggleMask = kDopMarkerA ^ kDopMarkerB = 0x05 ^ 0xFA = 0xFF.
        //
        // XOR with 0xFF bit-inverts the byte:
        //   0x05 ^ 0xFF = 0xFA  (marker A → marker B)
        //   0xFA ^ 0xFF = 0x05  (marker B → marker A)
        //
        // This is a single EOR Wn, Wn, #0xFF instruction on ARM64.
        // There is NO branch, NO conditional move, NO lookup table.
        marker ^= kDopToggleMask;
    }

    // Return the marker state to the caller so it can be forwarded as
    // `initial_marker` on the next call, maintaining the correct alternation
    // across multiple ring-buffer pushes within one playback session.
    return marker;
}

// ─────────────────────────────────────────────────────────────────────────────
// format_dop_from_interleaved_dsd — interleaved DSD input
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Pack interleaved MSBF DSD bytes into a DoP 1.1 stereo interleaved PCM stream.
 *
 * See dop_formatter.h for the full API contract and interleaved input layout.
 *
 * ### Input byte layout
 *
 * For frame i, the four source bytes are at:
 *
 *   src[4i+0]  →  Left  DSD byte 0  (bits [23:16] of the left  PCM word)
 *   src[4i+1]  →  Right DSD byte 0  (bits [23:16] of the right PCM word)
 *   src[4i+2]  →  Left  DSD byte 1  (bits [15: 8] of the left  PCM word)
 *   src[4i+3]  →  Right DSD byte 1  (bits [15: 8] of the right PCM word)
 *
 * This mirrors the byte-interleaved layout produced by FFmpeg for DSDIFF/DSF
 * files when decoded in non-planar (interleaved) mode.
 *
 * ### Implementation note
 *
 * Because the interleaved source strides are non-contiguous per channel, this
 * variant is slightly less cache-friendly than the planar form for very large
 * buffers.  For typical USB audio buffer sizes (≤ 4 KB), both L1-cache hit
 * rates are equivalent.
 *
 * @param dsd_interleaved  Interleaved DSD bytes (MSBF), 4 bytes per DoP frame.
 * @param pcm_out          Destination for interleaved 32-bit LE PCM.
 * @param num_dop_frames   DoP stereo frames to encode.
 * @param initial_marker   Starting marker; `kDopMarkerA` or `kDopMarkerB`.
 * @return                 Next marker for chaining across ring-buffer pushes.
 */
uint8_t format_dop_from_interleaved_dsd(
        const uint8_t *__restrict__ dsd_interleaved,
        uint8_t       *__restrict__ pcm_out,
        std::size_t                  num_dop_frames,
        uint8_t                      initial_marker) noexcept
{
    assert((num_dop_frames == 0 || dsd_interleaved != nullptr) &&
           "dsd_interleaved is null");
    assert((num_dop_frames == 0 || pcm_out != nullptr) && "pcm_out is null");
    assert((initial_marker == kDopMarkerA || initial_marker == kDopMarkerB) &&
           "initial_marker must be kDopMarkerA (0x05) or kDopMarkerB (0xFA)");

    const uint8_t *src = dsd_interleaved;
    uint8_t       *out = pcm_out;
    uint8_t        marker = initial_marker;

    for (std::size_t i = 0; i < num_dop_frames; ++i, src += 4, out += 8) {
        // Interleaved source layout for frame i:
        //   src[0] = Left  DSD byte 0  → PCM bits [23:16] of left  word
        //   src[1] = Right DSD byte 0  → PCM bits [23:16] of right word
        //   src[2] = Left  DSD byte 1  → PCM bits [15: 8] of left  word
        //   src[3] = Right DSD byte 1  → PCM bits [15: 8] of right word
        //
        // The byte ordering within each word is identical to the planar variant;
        // only the source strides differ (planar: +2 contiguous per channel;
        // interleaved: scattered at +0/+2 for L and +1/+3 for R).
        const uint32_t word_l =
            (static_cast<uint32_t>(marker) << 24u) |
            (static_cast<uint32_t>(src[0]) << 16u) |
            (static_cast<uint32_t>(src[2]) <<  8u);

        const uint32_t word_r =
            (static_cast<uint32_t>(marker) << 24u) |
            (static_cast<uint32_t>(src[1]) << 16u) |
            (static_cast<uint32_t>(src[3]) <<  8u);

        write_le32(out,     word_l);
        write_le32(out + 4, word_r);

        marker ^= kDopToggleMask;
    }

    return marker;
}

