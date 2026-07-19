// ─────────────────────────────────────────────────────────────────────────────
// libusb_event_thread.h
//
// Dedicated POSIX thread for libusb isochronous event processing.
//
// The libusb event model requires a thread that continuously calls
// libusb_handle_events_timeout_completed() to:
//   • Invoke isochronous OUT completion callbacks (iso_transfer_callback) as
//     the USB host controller DMA engine reports transfer outcomes.
//   • Process control-transfer completions used during device setup / teardown.
//   • Drive the libusb internal timer that handles transfer timeouts.
//
// Without a dedicated thread, no isochronous callback fires and the DAC
// receives no audio data regardless of how many transfers are submitted.
//
// ── Thread independence contract ─────────────────────────────────────────────
//
// This thread runs independently of:
//   • The Java / Kotlin UI thread (Main Looper).
//   • The FFmpeg decoder thread (audio producer, pushes to the SPSC ring).
//   • Any Android Binder / IPC thread.
//
// It communicates with the isochronous callback exclusively through:
//   • libusb's internal transfer queue (libusb-managed, lock-free hot path).
//   • TransferSlot::in_flight  (std::atomic<bool>, release/acquire).
//   • TransferSlot::shutdown   (std::atomic<bool>, release/acquire).
//   • IsoTransferPool::underrun_count_ (std::atomic<uint64_t>, relaxed).
//
// ── Cooperative shutdown protocol ────────────────────────────────────────────
//
// Normal teardown order (Step 10 driver orchestrator):
//
//   1. Mark all TransferSlot::shutdown = true.
//   2. Call libusb_cancel_transfer() on every in-flight transfer.
//   3. Wait for all TransferSlot::in_flight to become false
//      (the CANCELLED callbacks set them).
//   4. Call LibusbEventThread::stop().
//      stop() sets keep_running_ = false and calls libusb_interrupt_event_handler()
//      to wake the thread out of its current poll immediately, then joins.
//   5. Destroy IsoTransferPool (destructor calls release_all()).
//   6. Call uac2_release_interface().
//   7. libusb_close() / libusb_exit().
//
// The event thread MUST NOT be stopped (step 4) before all in-flight transfers
// have been cancelled and their callbacks have fired (step 3), because:
//   a) libusb_cancel_transfer() is asynchronous — the callback fires
//      only when the event loop processes the cancellation event.
//   b) Stopping the event loop before the callback fires leaves
//      TransferSlot::in_flight = true permanently, i.e. the pool can never
//      be released without undefined behaviour.
//
// ── Timeout rationale ────────────────────────────────────────────────────────
//
// kPollTimeoutUs = 100 000 µs = 100 ms.
//
// Each call to libusb_handle_events_timeout_completed() blocks for at most
// this duration if no events arrive.  Because USB HS isochronous transfers
// fire every 1 ms (8 packets at 125 µs each), the thread wakes up effectively
// continuously during active playback.  The 100 ms timeout only matters when:
//   • The pool is idle (no in-flight transfers — pre-playback or teardown).
//   • The USB host controller has a scheduling gap.
//
// 100 ms gives a responsive ~100 ms worst-case latency on stop().
// Smaller timeouts (e.g. 1 ms) would be more responsive at the cost of
// an unnecessary syscall every millisecond when the queue is empty.
//
// ── libusb_interrupt_event_handler ───────────────────────────────────────────
//
// When stop() is called it immediately wakes the blocked poll via
// libusb_interrupt_event_handler(), reducing shutdown latency from up to
// 100 ms to < 1 ms in practice.
// Available since libusb 1.0.21 (API 0x01000105).
//
// ── Thread scheduling ─────────────────────────────────────────────────────────
//
// The thread attempts to elevate to SCHED_FIFO with a low real-time priority
// (sched_get_priority_min(SCHED_FIFO) + 1).  This is best-effort:
//   • On unrooted Android with default SELinux policy the call will be denied
//     (EPERM); the thread continues at normal priority.
//   • On rooted devices or apps with the MODIFY_AUDIO_SETTINGS / real-time
//     scheduling capability, SCHED_FIFO is granted and the event loop cannot
//     be preempted by lower-priority UI threads.
//
// Even at normal priority the thread keeps up with 1 ms isochronous callbacks
// on all tested Android devices, because Android's audio scheduling window is
// 5 ms (SCHED_FIFO for AAudio threads).
//
// ─────────────────────────────────────────────────────────────────────────────

#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <string>
#include <thread>

// Forward declaration — libusb_context is owned by the JNI bridge layer.
struct libusb_context;

// ─────────────────────────────────────────────────────────────────────────────
// LibusbEventThread
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Manages a dedicated `std::thread` that drives the libusb event loop.
 *
 * ### Lifetime
 *
 * Use `LibusbEventThread::create()` instead of direct construction.  The thread
 * is started inside the factory; the object is in the running state immediately
 * after `create()` returns successfully.
 *
 * The thread remains running until `stop()` is called explicitly or until the
 * destructor fires (which calls `stop()` automatically).  Callers must follow
 * the cooperative shutdown protocol described in the module header before
 * stopping the thread.
 *
 * ### Thread identity
 *
 * The event thread runs with the name `"usb_ev"` (visible in Android Studio
 * CPU profiler and `adb shell ps -T`).  It is independent of every other thread
 * in the process — no mutex is shared between this thread and the audio
 * producer thread.
 *
 * @see iso_transfer_callback
 * @see IsoTransferPool
 */
class LibusbEventThread {
public:
    // ── Factory ───────────────────────────────────────────────────────────────

    /**
     * Create and immediately start the libusb event thread.
     *
     * On success the thread is running and `is_running()` returns `true`.
     * On failure returns `nullptr` and, if `error_out` is non-null, writes a
     * human-readable description of the failure.
     *
     * Failure causes:
     *   • `ctx` is null.
     *   • The underlying `std::thread` constructor throws `std::system_error`
     *     (kernel thread-creation limit reached — extremely rare).
     *
     * @param ctx        The libusb context owning the transfers to be serviced.
     *                   Must remain valid for the entire lifetime of this object.
     * @param error_out  Optional output string for diagnostic messages.
     * @return           Owning `unique_ptr` to the running thread object, or nullptr.
     */
    static std::unique_ptr<LibusbEventThread> create(
            libusb_context *ctx,
            std::string    *error_out = nullptr);

    /**
     * Signal the event thread to stop and block until it exits.
     *
     * Equivalent to calling `stop()` explicitly.  Safe to call even if `stop()`
     * was already called — the destructor is idempotent.
     *
     * ⚠️  Callers must complete the cooperative shutdown protocol (cancel all
     * in-flight transfers and wait for callbacks) BEFORE this destructor runs.
     * Destroying the object with in-flight transfers in the pool is undefined
     * behaviour because the pool's callback will dereference freed memory.
     */
    ~LibusbEventThread() noexcept;

    // Non-copyable, non-movable.
    // std::atomic and std::thread are neither copyable; internal raw pointers
    // must remain stable after construction.
    LibusbEventThread(const LibusbEventThread &)            = delete;
    LibusbEventThread &operator=(const LibusbEventThread &) = delete;
    LibusbEventThread(LibusbEventThread &&)                 = delete;
    LibusbEventThread &operator=(LibusbEventThread &&)      = delete;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Signal the event thread to stop and block until it exits.
     *
     * Internally:
     *   1. Sets `keep_running_` to `false` with `memory_order_release`.
     *   2. Calls `libusb_interrupt_event_handler(ctx_)` to wake the thread
     *      out of its current poll immediately (< 1 ms wakeup latency).
     *   3. Calls `thread_.join()` to wait for the thread function to return.
     *
     * Safe to call multiple times — subsequent calls are no-ops because
     * `thread_.joinable()` returns `false` after a successful join.
     *
     * Must NOT be called from the event thread itself (would deadlock on join).
     * The implementation asserts this in debug builds.
     */
    void stop() noexcept;

    // ── Status ────────────────────────────────────────────────────────────────

    /**
     * Returns `true` while the event thread is alive.
     *
     * Transitions from `true` → `false` only after `stop()` has been called
     * AND the thread has fully exited (joined).  The value is updated with
     * `memory_order_release` by the thread function as its last action, and
     * loaded with `memory_order_acquire` here to guarantee visibility.
     *
     * @return  `true` if the thread is running; `false` after `stop()`.
     */
    [[nodiscard]] bool is_running() const noexcept {
        return running_.load(std::memory_order_acquire);
    }

    /**
     * Returns the OS thread identifier for diagnostic / profiling use.
     *
     * Returns a default-constructed (invalid) `std::thread::id` if the thread
     * has not started or has already been joined.
     *
     * @return  `std::thread::id` of the event thread.
     */
    [[nodiscard]] std::thread::id thread_id() const noexcept {
        return thread_.get_id();
    }

    /**
     * Cumulative count of non-fatal event-loop errors (LIBUSB_ERROR_IO etc.)
     * observed since thread start.  Incremented with `memory_order_relaxed`;
     * a monitoring layer may poll this periodically.
     *
     * If the count reaches `kMaxConsecutiveErrors` the thread exits
     * autonomously and `is_running()` transitions to `false`.
     *
     * @return  Error event count since thread creation.
     */
    [[nodiscard]] uint32_t error_count() const noexcept {
        return error_count_.load(std::memory_order_relaxed);
    }

private:
    /**
     * Private constructor — use `create()` for validated, started construction.
     *
     * @param ctx  Non-null libusb context to service.
     */
    explicit LibusbEventThread(libusb_context *ctx) noexcept;

    /**
     * Event loop body — runs on the dedicated thread.
     *
     * Sequence on entry:
     *   1. Set thread name "usb_ev" via pthread_setname_np (Android debugger).
     *   2. Attempt SCHED_FIFO elevation (best-effort; continues on EPERM).
     *   3. Loop: call libusb_handle_events_timeout_completed() until
     *      `keep_running_` becomes false or consecutive-error threshold is hit.
     *   4. Store `running_ = false` with `memory_order_release` before exiting.
     */
    void thread_fn() noexcept;

    // ── Non-owning context pointer ────────────────────────────────────────────
    libusb_context *ctx_;

    // ── Shutdown flag ─────────────────────────────────────────────────────────
    /// Set to `false` by `stop()` (release) to signal the loop to exit.
    /// Read by the event thread (acquire) each iteration.
    std::atomic<bool> keep_running_{false};

    // ── Liveness flag ────────────────────────────────────────────────────────
    /// Set to `false` by `thread_fn()` (release) just before it returns.
    /// Read by `is_running()` (acquire) for external status queries.
    std::atomic<bool> running_{false};

    // ── Error telemetry ───────────────────────────────────────────────────────
    /// Cumulative non-fatal event-loop error count.  Relaxed — monitoring only.
    std::atomic<uint32_t> error_count_{0};

    // ── OS thread ─────────────────────────────────────────────────────────────
    /// The underlying POSIX thread.  Constructed last so all other members
    /// are fully initialised before the thread function references them.
    std::thread thread_;

    // ── Tuning constants ──────────────────────────────────────────────────────

    /// Poll timeout in microseconds (100 ms).
    /// The thread wakes on every USB event during active playback, so this
    /// timeout is rarely reached.  It bounds worst-case stop() latency.
    static constexpr long kPollTimeoutUs = 100'000L;

    /// Consecutive non-fatal errors beyond which the thread self-terminates.
    /// libusb returns LIBUSB_ERROR_IO if the USB host controller is wedged;
    /// 8 consecutive errors at up to 1 ms each = ~8 ms before forced exit.
    static constexpr uint32_t kMaxConsecutiveErrors = 8U;
};

