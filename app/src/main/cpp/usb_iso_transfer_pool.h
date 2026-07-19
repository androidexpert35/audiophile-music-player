// ─────────────────────────────────────────────────────────────────────────────
// usb_iso_transfer_pool.h
//
// RAII pool of pre-allocated libusb isochronous OUT transfer descriptors.
//
// Owns the complete memory lifecycle for a fixed-size ring of libusb_transfer
// structs and their aligned audio payload buffers.  Transfers are configured
// and silenced at construction; no USB transaction is initiated until Step 5
// calls libusb_submit_transfer() on each slot in the ring.
//
// ── USB High-Speed isochronous microframe arithmetic ────────────────────────
//
// USB 2.0 High-Speed divides time into 125 µs "microframes":
//   8 microframes per 1 ms frame × 8000 frames/sec = 8000 µframes/sec
//
// Audio data rate at sample_rate_hz with bytes_per_audio_frame bytes/sample:
//   bytes_per_sec = sample_rate_hz × bytes_per_audio_frame
//   bytes_per_uframe_exact = bytes_per_sec / 8000
//
// Because some sample rates (e.g., 44.1 kHz, 88.2 kHz, 352.8 kHz) are not
// evenly divisible by 8000, bytes_per_uframe is fractional.  The standard
// approach is ceiling-division: allocate the maximum possible bytes per packet
// and let the hardware (or a feedback endpoint in Step 5) regulate the actual
// count.  This wastes ≤ 1 sample per microframe but never under-allocates.
//
//   bytes_per_uframe_ceil = ceil(sample_rate_hz / 8000) × bytes_per_audio_frame
//
// Reference calculations for common sample rates / 32-bit stereo (8 B/frame):
//   44 100 Hz → ceil(5.5125)  ×8 =  6×8 =  48 B/µframe
//   48 000 Hz → ceil(6.0)     ×8 =  6×8 =  48 B/µframe
//   88 200 Hz → ceil(11.025)  ×8 = 12×8 =  96 B/µframe
//   96 000 Hz → ceil(12.0)    ×8 = 12×8 =  96 B/µframe
//  176 400 Hz → ceil(22.05)   ×8 = 23×8 = 184 B/µframe
//  192 000 Hz → ceil(24.0)    ×8 = 24×8 = 192 B/µframe  ← design target
//  352 800 Hz → ceil(44.1)    ×8 = 45×8 = 360 B/µframe  (DSD64 DoP)
//  384 000 Hz → ceil(48.0)    ×8 = 48×8 = 384 B/µframe
//
// Buffer sizes at 8 packets/transfer:
//   192 000 Hz → 192 B/µframe × 8 packets = 1 536 B/transfer
//   384 000 Hz → 384 B/µframe × 8 packets = 3 072 B/transfer  (HS max packet!)
//
// ── Pool sizing rationale ────────────────────────────────────────────────────
//
// N transfers × P packets/transfer = N×P microframes of buffered audio.
//
// With N=16, P=8:
//   16 × 8 = 128 µframes = 16 ms of pre-allocated audio in the ring.
//   At 192 kHz this holds 16 × 1536 = 24 576 bytes ≈ 24 KB.
//
// 16 ms provides adequate safety margin against CPU scheduling jitter on
// Android (SCHED_FIFO audio threads have ≤ 5 ms worst-case latency).
// Increasing to N=32 doubles buffer RAM and latency but reduces dropout risk
// on devices with poor USB host controller scheduling.
//
// ── Memory alignment ─────────────────────────────────────────────────────────
//
// Payload buffers are aligned to 64 bytes (ARM64 L1 cache line size).
// posix_memalign() is used instead of new/malloc because:
//   a) Cache-aligned buffers prevent false sharing between CPU and DMA engine.
//   b) DMA on some SoCs requires physically contiguous bus-aligned memory;
//      64-byte userspace alignment is a minimal hygiene measure.
//   c) posix_memalign() memory is compatible with free() — no special deleter.
//
// ── Thread-safety ────────────────────────────────────────────────────────────
//
// IsoTransferPool is NOT thread-safe by itself.
//   • Construction and destruction must occur on the same thread.
//   • After creation, libusb_submit_transfer() (Step 5) is called from the
//     audio producer thread.
//   • The completion callback fires on libusb's event thread.
//   • The caller (Step 5) is responsible for the synchronisation between
//     producer and callback via a lock-free SPSC ring.
//
// ─────────────────────────────────────────────────────────────────────────────

#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

// Forward declarations — the pool holds raw pointers but doesn't expose
// libusb internals to callers above the data layer.
struct libusb_transfer;
struct libusb_device_handle;

// Forward declaration — the pool holds a non-owning pointer to the SPSC ring
// buffer that feeds decoded audio bytes into isochronous transfer payloads.
// Including the full header here is intentionally avoided to minimise
// transitive coupling; callers that need the complete spsc_ring_buffer API
// include spsc_ring_buffer.h directly.
class SpscRingBuffer;

// ─────────────────────────────────────────────────────────────────────────────
// Pool configuration
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Immutable configuration for an IsoTransferPool.
 *
 * All fields are set at construction time and never mutated.
 *
 * @property sample_rate_hz          PCM sample rate in Hz (e.g., 192000).
 * @property bytes_per_audio_frame   Bytes per interleaved audio sample-frame
 *                                   (channels × bytes_per_sample, e.g., 8 for
 *                                   stereo 32-bit).
 * @property endpoint_address        USB isochronous OUT endpoint address from
 *                                   the Step 2 descriptor scanner.
 * @property pool_size               Number of transfer slots in the ring (N).
 *                                   Typical values: 8 (low-latency), 16
 *                                   (balanced), 32 (dropout-resistant).
 * @property packets_per_transfer    ISO packets per libusb_transfer struct (P).
 *                                   Each packet is one 125 µs USB microframe
 *                                   worth of audio.  Typical: 8 (1 ms/transfer).
 */
struct IsoTransferPoolConfig {
    uint32_t sample_rate_hz          = 192'000;
    uint32_t bytes_per_audio_frame   = 8;      ///< e.g., 2ch × 4 bytes = 8
    uint8_t  endpoint_address        = 0;
    uint32_t pool_size               = 16;     ///< N — number of transfer slots
    uint32_t packets_per_transfer    = 8;      ///< P — microframes per transfer
};

// ─────────────────────────────────────────────────────────────────────────────
// Per-slot context
// ─────────────────────────────────────────────────────────────────────────────

// Forward declare the pool so TransferSlot can hold a back-pointer.
class IsoTransferPool;

/**
 * Per-transfer context passed as `libusb_transfer::user_data`.
 *
 * The callback receives a raw `libusb_transfer*` pointer; dereferencing
 * `user_data` back to this struct lets the callback identify which slot
 * completed and reach the owning pool for re-submission logic.
 *
 * ### Memory ordering for `in_flight` and `shutdown`
 *
 * Both flags are written from one thread and read from another:
 *   • `in_flight`: producer thread writes true (submit), libusb event thread
 *     writes false (callback).  `std::atomic<bool>` with sequentially
 *     consistent ordering provides the necessary happens-before guarantee
 *     without a mutex.
 *   • `shutdown`:  producer/Kotlin thread writes true (teardown), libusb
 *     event thread reads it inside the callback.  The callback MUST check
 *     this flag before re-submitting — attempting to call
 *     libusb_submit_transfer() during teardown causes a use-after-free if
 *     the pool is already being destroyed.
 *
 * Layout: alignas(64) keeps the hot-path flags on a single cache line,
 * preventing false sharing between the producer thread (which reads
 * in_flight to find a free slot) and the event thread (which writes it).
 *
 * @property pool      Back-pointer to the owning IsoTransferPool.  Never null.
 * @property index     Zero-based slot index within the pool (0 … pool_size−1).
 * @property in_flight Atomic flag: true while submitted, false when callback fires.
 * @property shutdown  Atomic flag: set true before cancelling transfers on teardown.
 *                     The callback must NOT re-submit when this is true.
 * @property consecutive_errors Running count of consecutive non-COMPLETED statuses.
 *                     Reset to 0 on any COMPLETED callback.  When it reaches
 *                     MAX_CONSECUTIVE_ERRORS the callback sets shutdown=true and
 *                     stops re-submission, preventing an infinite error loop.
 */
struct alignas(64) TransferSlot {
    IsoTransferPool       *pool               = nullptr;
    uint32_t               index              = 0;
    std::atomic<bool>      in_flight          {false};
    std::atomic<bool>      shutdown           {false};
    uint32_t               consecutive_errors = 0;

    // Maximum tolerated consecutive non-COMPLETED statuses (excluding CANCELLED)
    // before the slot gives up re-submission.  A real DAC bus fault produces a
    // sustained stream of LIBUSB_TRANSFER_ERROR; 8 errors in a row (~1 ms at
    // 8 packets/transfer) is far beyond any transient glitch.
    static constexpr uint32_t MAX_CONSECUTIVE_ERRORS = 8U;

    // Default constructor — all members zero/false-initialised by their initialisers.
    TransferSlot() = default;

    // Explicit move constructor required because std::atomic<bool> deletes both
    // the copy and move constructors, which in turn makes TransferSlot itself
    // non-MoveInsertable.  std::vector::resize() demands MoveInsertable in C++17,
    // so without this the allocate() call that resizes slots_ fails to compile.
    // The atomic values are transferred with relaxed ordering — slots are only
    // moved during initial vector construction, before any thread observes them.
    TransferSlot(TransferSlot&& other) noexcept
        : pool              (other.pool),
          index             (other.index),
          in_flight         (other.in_flight.load(std::memory_order_relaxed)),
          shutdown          (other.shutdown.load(std::memory_order_relaxed)),
          consecutive_errors(other.consecutive_errors)
    {}

    // Move-assignment — same rationale as the move constructor.
    TransferSlot& operator=(TransferSlot&& other) noexcept {
        if (this != &other) {
            pool               = other.pool;
            index              = other.index;
            in_flight.store(other.in_flight.load(std::memory_order_relaxed),
                            std::memory_order_relaxed);
            shutdown.store(other.shutdown.load(std::memory_order_relaxed),
                           std::memory_order_relaxed);
            consecutive_errors = other.consecutive_errors;
        }
        return *this;
    }

    // Copy operations remain deleted — TransferSlot must not be copied while
    // in-flight flags are in use, as that would silently duplicate atomic state.
    TransferSlot(const TransferSlot&)            = delete;
    TransferSlot& operator=(const TransferSlot&) = delete;
};

// ─────────────────────────────────────────────────────────────────────────────
// IsoTransferPool
// ─────────────────────────────────────────────────────────────────────────────

/**
 * RAII manager for a pool of pre-allocated libusb isochronous OUT transfers.
 *
 * ### Ownership model
 *
 * For each slot i (0 … N−1) the pool owns:
 *   - `transfers_[i]`  — `libusb_transfer*` allocated with `libusb_alloc_transfer(P)`
 *   - `buffers_[i]`    — 64-byte-aligned payload buffer from `posix_memalign()`
 *   - `slots_[i]`      — `TransferSlot` stored in-line (no heap allocation)
 *
 * `transfers_[i]->buffer` points into `buffers_[i]`.
 * `transfers_[i]->user_data` points to `&slots_[i]`.
 * `LIBUSB_TRANSFER_FREE_BUFFER` is intentionally NOT set — the pool owns the
 * buffer and frees it explicitly in the destructor.
 *
 * ### Destruction precondition
 *
 * The destructor does NOT cancel in-flight transfers.  Step 5 must cancel and
 * wait for all in-flight transfers to complete (callback fires) before
 * destroying or resetting the pool.  Destroying a pool with in-flight transfers
 * is undefined behaviour (the callback would dereference freed memory).
 *
 * ### Factory pattern
 *
 * Use `IsoTransferPool::create()` instead of direct construction.  The factory
 * performs all fallible operations (posix_memalign, libusb_alloc_transfer) and
 * rolls back cleanly on any failure, guaranteeing the returned object is either
 * fully valid or nullptr.
 */
class IsoTransferPool {
public:
    /**
     * Allocate and configure the entire transfer pool.
     *
     * On success returns a fully initialised pool with all transfer buffers
     * zeroed and all `libusb_transfer` structs configured.  No transfer is
     * submitted.
     *
     * On failure returns `nullptr`.  If `error_out` is non-null it receives a
     * human-readable description of the first allocator error.  All partially
     * allocated resources are freed before returning nullptr.
     *
     * @param handle     Open device handle from Step 1.  Must not be null.
     * @param cfg        Pool configuration.  Validated inside the factory.
     * @param error_out  Optional output string for diagnostic messages.
     * @return           Owning unique_ptr, or nullptr on failure.
     */
    static std::unique_ptr<IsoTransferPool> create(
            libusb_device_handle        *handle,
            const IsoTransferPoolConfig &cfg,
            std::string                 *error_out = nullptr);

    /**
     * Releases all transfer structs and payload buffers.
     *
     * Precondition: no transfer in the pool is currently in-flight.
     * See class-level documentation for the teardown contract.
     */
    ~IsoTransferPool() noexcept;

    // Non-copyable, non-movable — internal pointers must stay stable
    // (TransferSlot::pool and libusb_transfer::user_data point into this object).
    IsoTransferPool(const IsoTransferPool &)            = delete;
    IsoTransferPool &operator=(const IsoTransferPool &) = delete;
    IsoTransferPool(IsoTransferPool &&)                 = delete;
    IsoTransferPool &operator=(IsoTransferPool &&)      = delete;

    // ── Accessors ─────────────────────────────────────────────────────────────

    /**
     * Read-only access to the transfer ring.
     *
     * Callers may inspect or submit individual transfers; they must never free
     * any entry directly.  Use the pool's destructor for lifecycle management.
     *
     * @return  Reference to the internal vector of N libusb_transfer pointers.
     */
    [[nodiscard]] const std::vector<libusb_transfer *> &transfers() const noexcept {
        return transfers_;
    }

    /**
     * Read-only access to per-slot context objects.
     *
     * Exposed so Step 5 can inspect in_flight state from the audio thread
     * without casting user_data on every callback.
     *
     * @return  Reference to the N-element slot vector.
     */
    [[nodiscard]] const std::vector<TransferSlot> &slots() const noexcept {
        return slots_;
    }

    /**
     * Mutable slot access required by the Step 5 submission logic to flip
     * in_flight flags.
     *
     * @return  Mutable reference to the N-element slot vector.
     */
    std::vector<TransferSlot> &slots() noexcept {
        return slots_;
    }

    /** Immutable pool configuration used to construct this instance. */
    [[nodiscard]] const IsoTransferPoolConfig &config() const noexcept { return cfg_; }

    /**
     * Bytes per USB microframe (125 µs), ceiling-rounded for fractional rates.
     *
     * This is the configured `iso_packet_desc[i].length` on every packet of
     * every transfer in the pool.
     *
     * @return  Bytes per micro-frame = ceil(sample_rate / 8000) × bytes_per_frame.
     */
    [[nodiscard]] uint32_t bytes_per_uframe()        const noexcept { return bytes_per_uframe_; }

    /**
     * Total payload buffer size allocated for each transfer, in bytes.
     *
     * @return  bytes_per_uframe × packets_per_transfer.
     */
    [[nodiscard]] uint32_t buffer_size_per_transfer() const noexcept { return buffer_size_per_xfer_; }

    /**
     * Total payload memory allocated across all transfers, in bytes.
     *
     * @return  buffer_size_per_transfer × pool_size.
     */
    [[nodiscard]] uint32_t total_buffer_bytes() const noexcept {
        return buffer_size_per_xfer_ * cfg_.pool_size;
    }

    // ── Ring buffer integration ────────────────────────────────────────────────

    /**
     * Attach the SPSC ring buffer that supplies decoded audio bytes to transfers.
     *
     * Must be called before the first `libusb_submit_transfer()`.  The pool
     * holds a **non-owning** pointer; the caller owns the ring buffer and must
     * keep it alive for the entire lifetime of the pool and all in-flight
     * transfers.
     *
     * Thread-safety: the pointer is stored with `memory_order_release` so the
     * isochronous callback thread (which loads it with `memory_order_acquire`)
     * always observes the attached ring after this call returns.  Do NOT call
     * this again while transfers are in-flight — replacing the ring mid-stream
     * is undefined behaviour.
     *
     * @param rb  Non-null pointer to the ring buffer owned by the audio engine.
     *            Passing nullptr disables ring consumption and reverts the
     *            callback to silence output (startup / teardown states).
     */
    void attach_ring_buffer(SpscRingBuffer *rb) noexcept {
        ring_buffer_.store(rb, std::memory_order_release);
    }

    /**
     * Returns the currently attached ring buffer, or nullptr if none is set.
     *
     * @return  Non-owning pointer to the attached ring buffer.
     */
    [[nodiscard]] SpscRingBuffer *ring_buffer() const noexcept {
        return ring_buffer_.load(std::memory_order_acquire);
    }

    // ── Underrun telemetry ─────────────────────────────────────────────────────

    /**
     * Returns the cumulative isochronous underrun count since construction
     * or the last `reset_underrun_count()` call.
     *
     * An underrun is recorded each time the callback cannot pop a full
     * transfer's worth of bytes from the ring and must zero-pad the remainder.
     * Sustained underruns indicate the FFmpeg decoder thread is falling behind
     * the USB isochronous data rate.
     *
     * Thread-safe: may be read from any thread at any time.
     *
     * @return  Cumulative underrun event count.
     */
    [[nodiscard]] uint64_t underrun_count() const noexcept {
        return underrun_count_.load(std::memory_order_relaxed);
    }

    /**
     * Reset the underrun counter to zero.
     *
     * Intended for periodic monitoring dashboards that measure underruns per
     * interval.  Calling this does NOT affect playback or buffer state.
     */
    void reset_underrun_count() noexcept {
        underrun_count_.store(0, std::memory_order_relaxed);
    }

    /**
     * Increment the underrun counter by one.
     *
     * Called from `iso_transfer_callback()` whenever the ring buffer cannot
     * supply a complete transfer's worth of audio data.
     *
     * Uses `memory_order_relaxed` — this is a monitoring counter; no audio
     * data correctness depends on its immediate cross-thread visibility.
     * Safe to call from any thread, including the libusb event thread.
     */
    void increment_underrun_count() noexcept {
        underrun_count_.fetch_add(1, std::memory_order_relaxed);
    }

    /**
     * Increment the cumulative ISO callback invocation counter and return its
     * previous value (0-based: first call returns 0).
     *
     * Used exclusively by the first-5-callbacks diagnostic trace inside
     * `iso_transfer_callback`.  Silenced automatically once `count >= 5` by
     * the caller so the counter imposes zero overhead during steady-state audio.
     *
     * @return  Zero-based invocation index before this increment.
     */
    uint32_t increment_callback_fire_count() noexcept {
        return callback_fire_count_.fetch_add(1, std::memory_order_relaxed);
    }

    // ── Bresenham fractional-rate packet sizing ────────────────────────────────
    //
    // USB High-Speed delivers exactly 8000 microframes per second.  For sample
    // rates that are NOT integer multiples of 8000 (44.1, 88.2, 176.4, 352.8 kHz
    // and DSD64/DSD128 byte rates), the ideal samples-per-µframe is fractional.
    //
    // The naive ceiling approach allocates ceil(sr/8000) samples per every µframe,
    // which over-delivers data:
    //   44 100 Hz × 8 B/frame → ideal 352 800 B/s
    //   Ceiling: ceil(5.5125) = 6 → 6 × 8000 × 8 = 384 000 B/s  (+8.8% ≡ pitch shift)
    //
    // The Bresenham (integer-DDA) accumulator distributes floor/ceil packet sizes
    // so the long-term average exactly matches the nominal sample rate:
    //   accumulator += sample_rate_hz          (add nominal rate each µframe)
    //   frames_this_µframe = acc / 8000        (integer floor)
    //   acc %= 8000                            (carry fractional remainder forward)
    //   bytes_this_µframe = frames × bpf
    //
    // Over any multiple of (8000 / gcd(sr, 8000)) consecutive µframes the
    // total byte count equals exactly sample_rate_hz × bytes_per_audio_frame /
    // 8000 per µframe — no pitch shift, no buffer overflow.

    /**
     * Compute per-packet byte counts for the next transfer using the Bresenham
     * phase accumulator, then advance the accumulator state.
     *
     * Called once per transfer resubmission from `iso_transfer_callback` (on the
     * libusb event thread) and once per slot from `allocate()` (on the JNI init
     * thread, before any submissions).  Both call sites are serialised — no
     * concurrent access to `fractional_accumulator_` is possible.
     *
     * The maximum bytes for any single packet is `ceil(sample_rate / 8000) ×
     * bytes_per_audio_frame = bytes_per_uframe_`, which is ≤ the allocated
     * `buffer_size_per_xfer_ / packets_per_transfer`.  No buffer overflow can occur.
     *
     * @param packet_lengths_out  Caller-provided array of at least
     *                            `packets_per_transfer` uint32_t elements.
     *                            On return, `packet_lengths_out[k]` is the
     *                            byte count for microframe packet k.
     * @return                    Sum of all packet byte counts; use this as the
     *                            new `libusb_transfer::length` when resubmitting.
     */
    uint32_t compute_next_transfer_lengths(uint32_t *packet_lengths_out) noexcept;

    // ── Initial transfer submission ────────────────────────────────────────────

    /**
     * Submit every pre-configured transfer slot to the USB host controller,
     * starting the isochronous OUT schedule.
     *
     * ### Ordering contract
     *
     * This function MUST be called AFTER:
     *   1. `attach_ring_buffer()` — the callback needs a ring to drain.
     *   2. The LibusbEventThread is running — the event loop must be alive to
     *      receive the first completion callbacks.
     *   3. The SPSC ring contains at least 50% capacity of real audio data —
     *      submitting with an empty ring causes the first callbacks to output
     *      sustained silence.  UAC2 DACs may respond with LIBUSB_TRANSFER_STALL
     *      after prolonged silence in their active streaming alt setting, which
     *      triggers `Triage::Shutdown` and permanently kills the callback chain.
     *
     * Sets `in_flight = true` with `memory_order_release` BEFORE calling
     * `libusb_submit_transfer()` so the callback can never observe `in_flight`
     * as false for a live in-flight transfer.
     *
     * @return  Number of slots successfully submitted (≥ 1), or -1 if any
     *          `libusb_submit_transfer` call fails.  On failure already-submitted
     *          transfers are NOT cancelled — the caller must invoke
     *          `teardown_context()` to drain and cancel all slots cleanly.
     */
    int submit_all_transfers() noexcept;

    /**
     * Reset all transfer slots to a clean, not-submitted state so that
     * `submit_all_transfers()` can be called again after a recoverable
     * failure (e.g., `LIBUSB_ERROR_BUSY` cleared by `libusb_clear_halt`).
     *
     * **Pre-condition**: Every slot must be confirmed NOT in-flight before
     * calling this function.  If `submit_all_transfers()` failed on slot 0
     * (the first slot — no transfers were ever submitted) this pre-condition
     * is trivially satisfied.  If any slots are already in-flight, calling
     * this function is undefined behaviour.
     *
     * Clears both `in_flight` and `shutdown` to `false` for all slots using
     * `memory_order_release` so subsequent `submit_all_transfers()` calls
     * observe the reset through the normal acquire-load on entry.
     */
    void reset_for_retry() noexcept;

    // ── DSD stall observation window ──────────────────────────────────────────
    //
    // Used by the DsdPlaybackManager (Step 13) to detect a LIBUSB_TRANSFER_STALL
    // that occurs within the first 200 ms of Native DSD streaming — a strong
    // signal that the DAC rejected the native DSD alt setting and fallback to
    // DoP is required.
    //
    // Call sequence (monitor thread):
    //   1. begin_dsd_observation_window()  — arm the window, record start epoch.
    //   2. iso_transfer_callback sets dsd_stall_in_window_ if STALL fires < 200 ms.
    //   3. Monitor thread polls is_dsd_stall_in_window() every 5 ms.
    //   4. clear_dsd_observation_window()  — disarm after 200 ms or on teardown.
    //
    // Thread safety: dsd_window_active_ uses release-store so the callback's
    // acquire-load on that flag guarantees dsd_window_start_ns_ is visible.
    // dsd_stall_in_window_ uses release-store / acquire-load for the same reason.

    /**
     * Arm the 200 ms DSD stall observation window.
     *
     * Records the current time as the window epoch and sets the window-active
     * flag with memory_order_release so that any subsequent acquire-load of the
     * flag in `iso_transfer_callback` guarantees the epoch is visible.
     *
     * Must be called from the JNI init thread, after all Native DSD transfers
     * have been submitted and the event thread is running.  Safe to call from
     * any thread as long as no other thread calls it concurrently.
     */
    void begin_dsd_observation_window() noexcept;

    /**
     * Disarm the 200 ms DSD stall observation window.
     *
     * Clears both flags so the callback no longer sets the stall indicator.
     * Should be called by the monitor thread when the window expires cleanly
     * or when fallback has been triggered (to prevent re-entry).
     */
    void clear_dsd_observation_window() noexcept;

    /**
     * Return true if a LIBUSB_TRANSFER_STALL was recorded inside the active
     * observation window.
     *
     * Loads dsd_stall_in_window_ with memory_order_acquire so the monitor thread
     * sees all state written by the callback before the stall flag was set.
     *
     * Thread-safe; may be called from any thread.
     *
     * @return  true when a stall occurred within the 200 ms observation window.
     */
    [[nodiscard]] bool is_dsd_stall_in_window() const noexcept {
        return dsd_stall_in_window_.load(std::memory_order_acquire);
    }

    /**
     * Record that a LIBUSB_TRANSFER_STALL fired inside the observation window.
     *
     * Called exclusively from `iso_transfer_callback` on the libusb event thread.
     * Uses memory_order_release so the monitor thread's acquire-load sees any
     * state that existed before the stall was raised.
     *
     * The pool checks timing internally (within 200 ms of `begin_dsd_observation_window()`
     * epoch) before calling this, so callers do not need to re-check timing.
     */
    void set_dsd_stall_in_window() noexcept {
        dsd_stall_in_window_.store(true, std::memory_order_release);
    }

    /**
     * Return true while the 200 ms DSD stall observation window is active.
     *
     * Used internally by the callback to gate stall recording.  Clients may
     * also read it to verify that the window is properly armed.
     *
     * @return  true between begin_dsd_observation_window() and clear_dsd_observation_window().
     */
    [[nodiscard]] bool is_dsd_observation_active() const noexcept {
        return dsd_window_active_.load(std::memory_order_acquire);
    }

    /**
     * Return the nanosecond epoch recorded when the observation window was armed.
     *
     * Exposed for use by iso_transfer_callback to compute elapsed time without
     * a system-call (the value is simply a cached steady_clock::now().time_since_epoch().count()).
     * Protected by the acquire/release pair on dsd_window_active_.
     *
     * @return  Nanosecond count from the steady_clock epoch, or 0 if not armed.
     */
    [[nodiscard]] int64_t dsd_window_start_ns() const noexcept {
        return dsd_window_start_ns_;
    }

    /// Duration of the DSD stall observation window in nanoseconds (200 ms).
    /// Exposed as public so the free function iso_transfer_callback can
    /// compare elapsed time without an additional virtual call or accessor.
    static constexpr int64_t kDsdObservationWindowNs = 200'000'000LL;

private:
    /**
     * Private constructor — only callable from `create()`.
     * Sets derived constants from cfg; does NOT allocate libusb resources.
     */
    explicit IsoTransferPool(const IsoTransferPoolConfig &cfg) noexcept;

    /**
     * Perform all fallible allocations after validating the config.
     *
     * @param handle     Open device handle used to configure transfers.
     * @param error_out  Receives the first error description on failure.
     * @return           true on success; false on any allocation failure.
     */
    bool allocate(libusb_device_handle *handle, std::string *error_out);

    /**
     * Release every transfer struct and buffer that was successfully allocated.
     *
     * Idempotent and noexcept — safe to call on a partially-constructed pool.
     * Called by both the destructor and the factory's error path.
     */
    void release_all() noexcept;

    IsoTransferPoolConfig              cfg_;
    uint32_t                           bytes_per_uframe_     = 0;
    uint32_t                           buffer_size_per_xfer_ = 0;

    /// Bresenham phase accumulator for fractional-sample-rate packet sizing.
    ///
    /// Holds the running fractional remainder (always in [0, USB_HS_UFRAMES_PER_SEC))
    /// across all microframe packets submitted by this pool.  Modified exclusively by
    /// compute_next_transfer_lengths(), which is called only from allocate() (before any
    /// submission) and from iso_transfer_callback (on the single libusb event thread).
    /// No atomic needed — both call sites are serialised.
    uint32_t                           fractional_accumulator_ = 0U;

    /// libusb_transfer* ring — one entry per pool slot.
    std::vector<libusb_transfer *>     transfers_;

    /// 64-byte-aligned audio payload buffers — parallel to transfers_.
    /// Each entry is freed with free() (posix_memalign returns free()-able memory).
    std::vector<void *>                buffers_;

    /// Per-slot context structs — stored inline (no heap allocation).
    /// transfers_[i]->user_data == &slots_[i].
    std::vector<TransferSlot>          slots_;

    // ── Ring buffer + telemetry ────────────────────────────────────────────────

    /// Non-owning pointer to the SPSC ring buffer supplying decoded audio bytes.
    /// Stored as atomic so the release-store in attach_ring_buffer() is
    /// guaranteed to be visible to the event thread's acquire-load in the
    /// callback before any transfer is submitted.
    std::atomic<SpscRingBuffer *>      ring_buffer_{nullptr};

    /// Cumulative count of isochronous underrun events.
    /// Incremented by the callback (relaxed) when the ring cannot supply a full
    /// transfer; read by the monitoring layer via underrun_count().
    std::atomic<uint64_t>              underrun_count_{0};

    /// Cumulative ISO callback invocation count.
    /// Incremented (relaxed) inside iso_transfer_callback for the first-5-
    /// callbacks diagnostic trace.  Not a correctness-critical counter;
    /// relaxed ordering is sufficient since no audio data ordering is implied.
    std::atomic<uint32_t>              callback_fire_count_{0};

    // ── DSD stall observation window (Step 13) ────────────────────────────────

    /// Set to true by iso_transfer_callback when LIBUSB_TRANSFER_STALL fires
    /// while the observation window is active and within the 200 ms horizon.
    /// Read by the monitor thread; cleared by clear_dsd_observation_window().
    std::atomic<bool>                  dsd_stall_in_window_{false};

    /// Steady-clock epoch (nanoseconds) recorded in begin_dsd_observation_window().
    /// Protected by acquire/release pairing on dsd_window_active_; safe to read
    /// in the callback after an acquire-load of dsd_window_active_ is true.
    int64_t                            dsd_window_start_ns_{0};

    /// True while the 200 ms DSD stall detection window is armed.
    /// Written with memory_order_release so the callback's acquire-load sees
    /// dsd_window_start_ns_ after the window is armed.
    std::atomic<bool>                  dsd_window_active_{false};
};

// ─────────────────────────────────────────────────────────────────────────────
// Production isochronous completion callback (definition in .cpp)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Isochronous OUT transfer completion callback — production implementation.
 *
 * Registered into every libusb_transfer slot by IsoTransferPool::allocate().
 * Fires on libusb's internal event-handling thread for every completed,
 * cancelled, or errored submission.
 *
 * ### What this callback does (in order, fastest-path first)
 *
 *  1. **Shutdown guard** — if `slot->shutdown` is set, clears `in_flight` and
 *     returns immediately.  No re-submission.  This is the CANCELLED path
 *     during driver teardown.
 *
 *  2. **Transfer-level status dispatch** — evaluates `transfer->status`:
 *     - `COMPLETED`  → iterate iso packets, zero-fill the buffer, resubmit.
 *     - `ERROR`      → transient bus fault; zero-fill, resubmit (stream
 *                       continuity maintained; DAC PLL holds over one glitch).
 *     - `STALL`      → fatal per-endpoint error; set shutdown, do not resubmit.
 *                       Step 6 / Kotlin layer must call libusb_clear_halt().
 *     - `NO_DEVICE`  → DAC disconnected; set shutdown, do not resubmit.
 *     - `CANCELLED`  → planned by teardown; clear in_flight, do not resubmit.
 *     - `TIMED_OUT`  → impossible with timeout=0; treated as transient ERROR.
 *     - `OVERFLOW`   → impossible for OUT; treated as fatal (set shutdown).
 *
 *  3. **Consecutive-error guard** — `slot->consecutive_errors` is incremented
 *     on every non-COMPLETED status (excluding CANCELLED / NO_DEVICE / STALL
 *     which already set shutdown).  If it reaches `MAX_CONSECUTIVE_ERRORS`,
 *     shutdown is set and re-submission stops, preventing an infinite tight
 *     loop on a broken USB bus.
 *
 *  4. **iso_packet_desc walk** — only on COMPLETED and recoverable ERROR.
 *     Checks each packet's individual status and actual_length.  Short packets
 *     (actual_length < descriptor length) are noted via an atomic counter
 *     on the pool for Step 6 feedback-endpoint adjustment.
 *
 *  5. **Silent buffer refill** — `memset(transfer->buffer, 0, transfer->length)`
 *     fills the entire transfer's buffer with mathematical zeros before
 *     re-submission.  This guarantees absolute digital silence when the audio
 *     producer ring has no data ready, and ensures no stale audio data is
 *     re-sent on error paths.
 *
 *  6. **`libusb_submit_transfer()`** — re-enqueues the transfer on the USB host
 *     controller's isochronous schedule.  If submit fails (e.g. LIBUSB_ERROR_NO_DEVICE),
 *     `shutdown` is set and `in_flight` is cleared without further retry.
 *
 * ### ABSOLUTE THREADING CONSTRAINTS — violations cause deadlocks or crashes
 *
 *   ✅ Uses only lock-free primitives: `std::atomic<bool>` store/load,
 *      `memset`, `libusb_submit_transfer`.
 *   ✅ `in_flight` is cleared with `memory_order_release` so the producer
 *      thread sees the cleared flag only after all buffer writes complete.
 *   ✅ `libusb_submit_transfer()` is safe to call from inside a callback on
 *      libusb's multi-threaded event-handling backend (Android uses pthreads).
 *      It is NOT safe on a single-threaded event loop — but Android's libusb
 *      backend always uses the threaded model.
 *   ❌ No mutex, no condition_variable, no std::string construction.
 *   ❌ No heap allocation of any kind.
 *   ❌ No `__android_log_print` on the hot COMPLETED path — log only on
 *      exceptional status codes that fire infrequently.
 *
 * @param transfer  The completed transfer.  `transfer->user_data` is a
 *                  non-null `TransferSlot*` set during pool construction.
 */
void iso_transfer_callback(libusb_transfer *transfer);

