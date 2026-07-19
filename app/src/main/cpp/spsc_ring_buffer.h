// ─────────────────────────────────────────────────────────────────────────────
// spsc_ring_buffer.h
//
// Lock-free Single-Producer Single-Consumer (SPSC) byte ring buffer.
//
// Bridges the FFmpeg decoding thread (Producer) and the libusb isochronous
// OUT completion callback (Consumer) without any mutex, spinlock, or blocking
// primitive.
//
// ── Design contract ──────────────────────────────────────────────────────────
//
// Only TWO threads may ever use this buffer simultaneously:
//   • PRODUCER — calls push() exclusively.  Only one producer at a time.
//   • CONSUMER — calls pop()  exclusively.  Only one consumer at a time.
//
// Any other usage pattern (MPSC, MPMC, etc.) introduces data races that are
// NOT detected at compile time.  This is intentional: the extra atomics needed
// for multi-producer safety are incompatible with the real-time callback constraint.
//
// ── Memory ordering rationale ────────────────────────────────────────────────
//
// Both read and write indices are monotonically increasing size_t values that
// never explicitly wrap.  Modulo-addressing is done with a bitmask (fast) rather
// than the % operator.  This is safe because buffer capacity is always a power of two.
//
// Happens-before chain for push():
//
//   [Producer]  writes payload bytes into buffer_[]
//               stores write_idx_ with memory_order_release
//                 ─── synchronises-with ───
//   [Consumer]  loads  write_idx_ with memory_order_acquire
//               then reads payload bytes from buffer_[]  ✅ all writes visible
//
// Happens-before chain for pop():
//
//   [Consumer]  reads  payload bytes from buffer_[]
//               stores read_idx_  with memory_order_release
//                 ─── synchronises-with ───
//   [Producer]  loads  read_idx_  with memory_order_acquire
//               then inspects free space               ✅ read pointer moved
//
// Self-reads (producer loads write_idx_, consumer loads read_idx_) use
// memory_order_relaxed — there is no other thread to synchronise with for the
// thread's own index.
//
// ── Cache-line isolation ─────────────────────────────────────────────────────
//
// Write index is on cache line A (producer hot data).
// Read  index is on cache line B (consumer hot data).
//
// Without this separation, a write to write_idx_ would invalidate the cache
// line containing read_idx_ on the consumer core, causing a round-trip over the
// inter-core coherency bus (~40 ns on Cortex-A75) every millisecond.  With
// isolation, each core operates on its own cache line at L1 speed (~4 cycles).
//
// ── Buffer sizing ─────────────────────────────────────────────────────────────
//
// Recommended minimum for the USB audio use case (192 kHz / 32-bit stereo):
//
//   Transfer pool fills 16 ms worth of audio per trip (~24 KB per refill cycle).
//   Decoding jitter headroom: 3× → 72 KB.
//   Next power of two: 128 KB.
//
// 128 KB provides ~43 ms of audio at 192 kHz / 32-bit stereo before an audio
// dropout occurs even if the decoder thread is fully preempted.  This is the
// recommended default capacity passed to SpscRingBuffer::create().
//
// ── Integer arithmetic overflow safety ───────────────────────────────────────
//
// write_idx_ − read_idx_ is computed as unsigned size_t subtraction.
// Overflow is safe: both values increase monotonically and their difference is
// always < capacity_ (< 2^20 for any sane audio buffer), so the difference
// never approaches SIZE_MAX / 2.
//
// ─────────────────────────────────────────────────────────────────────────────

#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <string>

// ─────────────────────────────────────────────────────────────────────────────
// SpscRingBuffer
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Lock-free, pre-allocated Single-Producer Single-Consumer byte ring buffer.
 *
 * Designed to bridge the FFmpeg decoder thread and the libusb isochronous
 * completion callback with zero locking overhead on the hot audio path.
 *
 * ### Thread-safety guarantee
 *
 * Exactly one producer thread calls push(); exactly one consumer thread calls
 * pop().  All other public methods (size(), free_space(), capacity()) are safe
 * to call from either thread as diagnostic / monitoring reads, but their results
 * are approximate snapshots (the other thread may concurrently mutate an index).
 *
 * ### Lifetime
 *
 * Use SpscRingBuffer::create() instead of direct construction.  The factory
 * validates and rounds up the requested capacity, allocates the payload buffer
 * once on the heap, and returns a fully-initialised object or nullptr on failure.
 * No further heap allocation occurs after creation.
 *
 * @see SpscRingBuffer::create
 * @see SpscRingBuffer::push
 * @see SpscRingBuffer::pop
 */
class SpscRingBuffer {
public:
    // ── Factory ───────────────────────────────────────────────────────────────

    /**
     * Allocate and initialise a ring buffer of at least `min_capacity` bytes.
     *
     * The actual capacity is the smallest power of two ≥ `min_capacity`.
     * This enables index-to-offset conversion via a fast bitmask instead of
     * the modulo operator.
     *
     * On success returns a fully initialised buffer with both indices at zero.
     * On failure returns nullptr and, if `error_out` is non-null, writes a
     * human-readable diagnostic string.
     *
     * @param min_capacity  Minimum ring size in bytes.  Must be > 0 and
     *                      ≤ (SIZE_MAX / 2) to guarantee safe unsigned arithmetic.
     * @param error_out     Optional output string for diagnostic messages.
     * @return              Owning unique_ptr to the initialised buffer, or nullptr.
     */
    static std::unique_ptr<SpscRingBuffer> create(
            std::size_t  min_capacity,
            std::string *error_out = nullptr);

    /**
     * Default destructor — releases the pre-allocated payload buffer.
     *
     * Safe to call while neither producer nor consumer is actively using the
     * buffer.  If called while concurrent access is ongoing the behaviour is
     * undefined — the caller is responsible for draining or stopping both
     * threads before destruction.
     */
    ~SpscRingBuffer() = default;

    // Non-copyable, non-movable.
    // Atomics are not copyable; the internal buffer pointer must stay stable.
    SpscRingBuffer(const SpscRingBuffer &)            = delete;
    SpscRingBuffer &operator=(const SpscRingBuffer &) = delete;
    SpscRingBuffer(SpscRingBuffer &&)                 = delete;
    SpscRingBuffer &operator=(SpscRingBuffer &&)      = delete;

    // ── Producer API (call only from the single producer thread) ──────────────

    /**
     * Atomically write `len` bytes from `data` into the ring.
     *
     * Non-blocking — returns immediately if insufficient free space.
     * Handles internal wrap-around with at most two memcpy calls.
     *
     * Memory ordering: stores write_idx_ with `memory_order_release` after
     * writing payload, so the consumer sees the committed bytes as soon as it
     * loads write_idx_ with `memory_order_acquire`.
     *
     * @param data  Source byte array.  Must not be null when len > 0.
     * @param len   Number of bytes to write.  If 0, returns true immediately.
     *              Must be ≤ capacity() or the call returns false.
     * @return      true  — bytes were written; the ring now contains len more bytes.
     *              false — insufficient free space; the ring is unchanged.
     */
    bool push(const uint8_t *data, std::size_t len) noexcept;

    // ── Consumer API (call only from the single consumer thread) ──────────────

    /**
     * Atomically read `len` bytes from the ring into `dest`.
     *
     * Non-blocking — returns immediately if insufficient data is available.
     * Handles internal wrap-around with at most two memcpy calls.
     *
     * Memory ordering: stores read_idx_ with `memory_order_release` after
     * reading payload, so the producer sees the freed space as soon as it
     * loads read_idx_ with `memory_order_acquire`.
     *
     * @param dest  Destination byte array.  Must not be null when len > 0.
     * @param len   Number of bytes to read.  If 0, returns true immediately.
     *              Must be ≤ capacity() or the call returns false.
     * @return      true  — bytes were read into dest; the ring now contains len fewer bytes.
     *              false — insufficient data available; dest is unchanged.
     */
    bool pop(uint8_t *dest, std::size_t len) noexcept;

    // ── Diagnostic / monitoring (approximate — may be called from either thread) ─

    /**
     * Returns the number of bytes currently available to read.
     *
     * Result is a conservative snapshot; by the time the caller acts on it the
     * producer may have added more bytes.  Use as a guide, not a guarantee.
     *
     * @return  Bytes available for pop(), in the range [0, capacity()].
     */
    std::size_t size() const noexcept;

    /**
     * Returns the number of bytes that can be written without blocking.
     *
     * Result is a conservative snapshot; by the time the caller acts on it the
     * consumer may have freed more space.  Use as a guide, not a guarantee.
     *
     * @return  Bytes available for push(), in the range [0, capacity()].
     */
    std::size_t free_space() const noexcept;

    /**
     * Power-of-two ring capacity in bytes, fixed at construction time.
     *
     * @return  Total ring size in bytes.
     */
    std::size_t capacity() const noexcept { return capacity_; }

    /**
     * Reset both indices to zero.
     *
     * ⚠️  ONLY safe when no producer or consumer thread is concurrently active.
     * Intended for unit tests and re-initialisation between playback sessions,
     * not for use during live audio streaming.
     */
    void reset() noexcept;

private:
    /**
     * Private constructor — use create() for validated construction.
     *
     * @param capacity  Must be a power of two; validated by the factory.
     */
    explicit SpscRingBuffer(std::size_t capacity) noexcept;

    // ── Ring geometry ─────────────────────────────────────────────────────────

    /// Ring size in bytes.  Always a power of two after construction.
    std::size_t capacity_;

    /// Bitmask for fast index-to-offset conversion: offset = index & mask_.
    /// Equivalent to (index % capacity_) but avoids division.
    std::size_t mask_;

    /// Pre-allocated, plain byte storage for the ring payload.
    /// Allocated once in the constructor via std::make_unique<uint8_t[]>.
    /// No further heap allocation occurs during push/pop.
    std::unique_ptr<uint8_t[]> buffer_;

    // ── Cache-line-isolated indices ───────────────────────────────────────────
    //
    // Each index lives on its own 64-byte cache line (ARM64 L1 cache line = 64 B).
    // This prevents false sharing: a release-store to write_idx_ by the producer
    // does NOT invalidate the cache line that holds read_idx_ on the consumer core,
    // and vice versa.
    //
    // Layout visualisation (64-byte cache lines):
    //
    //   CL-0  [write_idx_ | (56 bytes padding)]   ← producer core's hot line
    //   CL-1  [read_idx_  | (56 bytes padding)]   ← consumer core's hot line
    //
    // Without alignas(64), both atomics would share one cache line and each
    // store would trigger a round-trip cross-core invalidation (~40 ns on
    // Cortex-A75), creating ~40 µs of artificial latency per millisecond of audio.

    /// Monotonically increasing write cursor.  Only the producer increments this.
    /// Physical offset = write_idx_ & mask_.
    alignas(64) std::atomic<std::size_t> write_idx_{0};

    /// Monotonically increasing read cursor.  Only the consumer increments this.
    /// Physical offset = read_idx_ & mask_.
    alignas(64) std::atomic<std::size_t> read_idx_{0};
};


