// ─────────────────────────────────────────────────────────────────────────────
// spsc_ring_buffer.cpp
//
// Lock-free SPSC byte ring buffer — implementation.
//
// See spsc_ring_buffer.h for the full design rationale, memory ordering proof,
// cache-line isolation strategy, and thread-safety contract.
// ─────────────────────────────────────────────────────────────────────────────

#include "spsc_ring_buffer.h"

#include <android/log.h>
#include <algorithm>   // std::min
#include <cassert>
#include <cstring>     // memcpy
#include <limits>      // std::numeric_limits

// ─── Logging macros ───────────────────────────────────────────────────────────

static constexpr const char *SPSC_TAG = "SpscRingBuffer";

#define SPSC_LOGI(...) __android_log_print(ANDROID_LOG_INFO,  SPSC_TAG, __VA_ARGS__)
#define SPSC_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, SPSC_TAG, __VA_ARGS__)
#define SPSC_LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, SPSC_TAG, __VA_ARGS__)

// ─────────────────────────────────────────────────────────────────────────────
// Internal helpers
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Returns true when `n` is a power of two (and non-zero).
 *
 * Used to assert that the capacity passed to the constructor is valid.
 * The factory always calls next_power_of_two() before constructing, so
 * this should never fire in production.
 */
static constexpr bool is_power_of_two(std::size_t n) noexcept {
    return n != 0 && (n & (n - 1)) == 0;
}

/**
 * Round `n` up to the next power of two ≥ `n`.
 *
 * Uses the standard bit-widening trick:
 *   subtract 1, then set all bits below the highest set bit,
 *   then add 1 to obtain the next power of two.
 *
 * Special cases:
 *   n == 0  → returns 1  (minimum meaningful ring size)
 *   n already a power of two → returns n  (no rounding needed)
 *
 * Overflow guard: if n > SIZE_MAX/2 the result would overflow size_t.
 * The factory validates this before calling.
 *
 * @param n  Requested minimum capacity.
 * @return   Smallest power of two ≥ n.
 */
static std::size_t next_power_of_two(std::size_t n) noexcept {
    if (n == 0) return 1;
    if (is_power_of_two(n)) return n;

    --n;
    // Propagate highest set bit across all lower positions.
    // Loop handles both 32-bit and 64-bit size_t portably.
    for (std::size_t shift = 1; shift < sizeof(std::size_t) * 8; shift <<= 1) {
        n |= (n >> shift);
    }
    return n + 1;
}

// ─────────────────────────────────────────────────────────────────────────────
// SpscRingBuffer — private constructor
// ─────────────────────────────────────────────────────────────────────────────

SpscRingBuffer::SpscRingBuffer(std::size_t capacity) noexcept
    : capacity_(capacity),
      mask_(capacity - 1),                              // power-of-two invariant
      buffer_(std::make_unique<uint8_t[]>(capacity))    // zero-initialised by default
{
    // Defensive: capacity_ must be power of two for mask_ to work correctly.
    // If this fires, next_power_of_two() in the factory has a bug.
    assert(is_power_of_two(capacity_) && "SpscRingBuffer: capacity is not a power of two");
}

// ─────────────────────────────────────────────────────────────────────────────
// SpscRingBuffer::create — factory
// ─────────────────────────────────────────────────────────────────────────────

std::unique_ptr<SpscRingBuffer> SpscRingBuffer::create(
        std::size_t  min_capacity,
        std::string *error_out)
{
    // ── Validate ──────────────────────────────────────────────────────────────
    auto fail = [&](const char *msg) -> std::unique_ptr<SpscRingBuffer> {
        SPSC_LOGE("SpscRingBuffer::create: %s", msg);
        if (error_out) *error_out = msg;
        return nullptr;
    };

    if (min_capacity == 0)
        return fail("min_capacity must be > 0");

    // Guard against overflow in next_power_of_two().
    // SIZE_MAX / 2 is the highest value that can be rounded up to a power of two
    // without overflowing size_t.
    constexpr std::size_t kMaxSafeCapacity = std::numeric_limits<std::size_t>::max() / 2 + 1;
    if (min_capacity > kMaxSafeCapacity)
        return fail("min_capacity exceeds maximum safe power-of-two size");

    // ── Round up to power of two ──────────────────────────────────────────────
    const std::size_t capacity = next_power_of_two(min_capacity);

    SPSC_LOGI("SpscRingBuffer::create: requested=%zu  actual=%zu B  (%.1f KB)",
              min_capacity, capacity, static_cast<double>(capacity) / 1024.0);

    // ── Allocate ──────────────────────────────────────────────────────────────
    // Use new(std::nothrow) to avoid throwing across the JNI boundary.
    auto *raw = new (std::nothrow) SpscRingBuffer(capacity);
    if (raw == nullptr)
        return fail("out of memory allocating SpscRingBuffer");

    std::unique_ptr<SpscRingBuffer> ring(raw);

    // Verify the payload buffer was successfully allocated by make_unique.
    if (ring->buffer_ == nullptr)
        return fail("out of memory allocating ring payload buffer");

    SPSC_LOGI("SpscRingBuffer::create: SUCCESS — capacity=%zu B  mask=0x%zX  "
              "write_idx alignment=%zu  read_idx alignment=%zu",
              ring->capacity_,
              ring->mask_,
              alignof(decltype(ring->write_idx_)),
              alignof(decltype(ring->read_idx_)));

    return ring;
}

// ─────────────────────────────────────────────────────────────────────────────
// SpscRingBuffer::push — producer path
// ─────────────────────────────────────────────────────────────────────────────

/**
 * push() — write `len` bytes into the ring from the producer thread.
 *
 * ### Execution path (annotated for the hot case where data fits)
 *
 * 1. Load write_idx_ with relaxed — the producer is the only writer,
 *    no acquire needed for its own value.
 *
 * 2. Load read_idx_ with acquire — the consumer is the writer; acquire
 *    pairs with the consumer's release-store of read_idx_ in pop().
 *    This gives us visibility of all bytes the consumer has already consumed
 *    (freed ring space we may now safely overwrite).
 *
 * 3. Compute free space = capacity_ - (write - read).
 *    Unsigned subtraction wraps correctly as long as (write - read) < capacity_,
 *    which the ring invariant guarantees.
 *
 * 4. If free < len: return false.  Ring unchanged.  Zero allocations.
 *
 * 5. Compute the physical write offset: write_pos = write & mask_.
 *
 * 6. Copy in at most two chunks to handle wrap-around:
 *      first_chunk = min(len, capacity_ - write_pos)   // bytes until end of ring
 *      second_chunk = len - first_chunk                // bytes from ring start (0 if no wrap)
 *
 * 7. Store write_idx_ with release — makes the written bytes visible to
 *    the consumer's acquire-load of write_idx_ in pop().
 *
 * ### Why the write is safe without any additional fence
 *
 * On ARM64 (and x86-64), a store-release translates to:
 *   ARM64: STLR (Store-Release Register) — full one-way barrier
 *   x86-64: MOV  + implicit TSO ordering
 * The preceding memcpy calls happen entirely before the release store.
 * The consumer, upon observing the new write_idx_ via acquire-load, is
 * guaranteed to observe all the bytes written before the release store.
 */
bool SpscRingBuffer::push(const uint8_t *data, std::size_t len) noexcept {
    if (__builtin_expect(len == 0, 0)) return true;

    // ── Step 1 & 2: snapshot both cursors ─────────────────────────────────────
    const std::size_t write = write_idx_.load(std::memory_order_relaxed);
    const std::size_t read  = read_idx_.load(std::memory_order_acquire);

    // ── Step 3: check capacity ────────────────────────────────────────────────
    // free_bytes = capacity_ - (write - read)
    // Uses unsigned wrap-safe subtraction; the difference is always < capacity_.
    const std::size_t used  = write - read;
    const std::size_t avail = capacity_ - used;

    if (__builtin_expect(avail < len, 0)) {
        // Not enough space — the decoder should slow down or drop the frame.
        // This is a normal back-pressure signal, not an error.
        return false;
    }

    // ── Step 4 & 5: wrap-aware two-chunk copy ─────────────────────────────────
    // Physical offset within the linearly-laid-out ring array.
    const std::size_t write_pos    = write & mask_;

    // Bytes from write_pos to the physical end of the ring array.
    const std::size_t tail_space   = capacity_ - write_pos;

    // first_chunk: bytes written contiguously starting at write_pos.
    // second_chunk: remaining bytes written from ring[0] if the write wraps.
    const std::size_t first_chunk  = std::min(len, tail_space);
    const std::size_t second_chunk = len - first_chunk;

    std::memcpy(buffer_.get() + write_pos, data, first_chunk);

    if (__builtin_expect(second_chunk > 0, 0)) {
        // Wrap-around: second half of the write starts at the ring's origin.
        std::memcpy(buffer_.get(), data + first_chunk, second_chunk);
    }

    // ── Step 6: publish the new write cursor ──────────────────────────────────
    // release-store: all byte writes above are complete before the consumer
    // can observe the updated index.
    write_idx_.store(write + len, std::memory_order_release);

    return true;
}

// ─────────────────────────────────────────────────────────────────────────────
// SpscRingBuffer::pop — consumer path
// ─────────────────────────────────────────────────────────────────────────────

/**
 * pop() — read `len` bytes from the ring into the consumer thread's `dest`.
 *
 * ### Execution path (mirror of push)
 *
 * 1. Load read_idx_  with relaxed — the consumer is the only writer.
 *
 * 2. Load write_idx_ with acquire — pairs with the producer's release-store
 *    of write_idx_ in push().  Guarantees all bytes deposited before that
 *    store are visible once we see the new write_idx_ value.
 *
 * 3. Compute available = write - read.  If < len: return false.
 *
 * 4. Two-chunk copy from ring at read_pos = read & mask_.
 *
 * 5. Store read_idx_ with release — notifies the producer that these bytes
 *    are free; the producer's acquire-load of read_idx_ will see the new value
 *    and update its free-space calculation accordingly.
 */
bool SpscRingBuffer::pop(uint8_t *dest, std::size_t len) noexcept {
    if (__builtin_expect(len == 0, 0)) return true;

    // ── Step 1 & 2: snapshot both cursors ─────────────────────────────────────
    const std::size_t read  = read_idx_.load(std::memory_order_relaxed);
    const std::size_t write = write_idx_.load(std::memory_order_acquire);

    // ── Step 3: check available bytes ────────────────────────────────────────
    const std::size_t avail = write - read;

    if (__builtin_expect(avail < len, 0)) {
        // Not enough data — the callback will output silence this cycle.
        return false;
    }

    // ── Step 4: wrap-aware two-chunk copy ────────────────────────────────────
    const std::size_t read_pos     = read & mask_;
    const std::size_t tail_data    = capacity_ - read_pos;

    const std::size_t first_chunk  = std::min(len, tail_data);
    const std::size_t second_chunk = len - first_chunk;

    std::memcpy(dest, buffer_.get() + read_pos, first_chunk);

    if (__builtin_expect(second_chunk > 0, 0)) {
        // Wrap-around: remainder of the read starts at ring's origin.
        std::memcpy(dest + first_chunk, buffer_.get(), second_chunk);
    }

    // ── Step 5: publish the new read cursor ──────────────────────────────────
    // release-store: all byte reads above are complete; producer's acquire-load
    // will see freed space only after these reads finish.
    read_idx_.store(read + len, std::memory_order_release);

    return true;
}

// ─────────────────────────────────────────────────────────────────────────────
// SpscRingBuffer — diagnostic accessors
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Returns a conservative snapshot of bytes available to read.
 *
 * Both indices are loaded with acquire to get a consistent (if not atomic)
 * snapshot.  Since there is no double-word compare-and-swap that could read
 * both atomically, the returned value may be stale by the time the caller
 * acts on it — treat it as an approximation only.
 */
std::size_t SpscRingBuffer::size() const noexcept {
    // acquire on write_idx_ to observe the latest producer commit.
    // acquire on read_idx_  to observe the latest consumer advance.
    // The producer and consumer each own one of these; cross-thread visibility
    // is guaranteed by the acquire semantics.
    const std::size_t w = write_idx_.load(std::memory_order_acquire);
    const std::size_t r = read_idx_.load(std::memory_order_acquire);
    return w - r;
}

/**
 * Returns a conservative snapshot of bytes available to write.
 */
std::size_t SpscRingBuffer::free_space() const noexcept {
    return capacity_ - size();
}

// ─────────────────────────────────────────────────────────────────────────────
// SpscRingBuffer::reset
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Reset the ring to the empty state.
 *
 * Uses seq_cst stores so that any thread observing the new index values is
 * guaranteed to also observe the reset.  This is a slow path (called only
 * between playback sessions) so the stricter ordering is acceptable.
 *
 * ⚠️  Caller must ensure no producer or consumer is concurrently active.
 */
void SpscRingBuffer::reset() noexcept {
    write_idx_.store(0, std::memory_order_seq_cst);
    read_idx_.store(0,  std::memory_order_seq_cst);
    SPSC_LOGD("SpscRingBuffer::reset: indices zeroed, capacity=%zu B", capacity_);
}

