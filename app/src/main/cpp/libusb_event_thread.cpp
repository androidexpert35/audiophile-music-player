// ─────────────────────────────────────────────────────────────────────────────
// libusb_event_thread.cpp
//
// Dedicated libusb event loop thread — implementation.
//
// See libusb_event_thread.h for the cooperative shutdown protocol, timeout
// rationale, and thread-scheduling justification.
// ─────────────────────────────────────────────────────────────────────────────

#include "libusb_event_thread.h"

#include <android/log.h>
#include <cerrno>
#include <cstring>       // strerror
#include <functional>
#include <pthread.h>     // pthread_setname_np, pthread_setschedparam
#include <sched.h>       // SCHED_FIFO, sched_get_priority_min
#include <sys/resource.h> // setpriority, PRIO_PROCESS
#include <unistd.h>      // gettid

#include "libusb/libusb.h"

// ─── Logging macros ───────────────────────────────────────────────────────────

static constexpr const char *EVT_TAG = "LibusbEventThread";

#define ELOGI(...) __android_log_print(ANDROID_LOG_INFO,  EVT_TAG, __VA_ARGS__)
#define ELOGW(...) __android_log_print(ANDROID_LOG_WARN,  EVT_TAG, __VA_ARGS__)
#define ELOGE(...) __android_log_print(ANDROID_LOG_ERROR, EVT_TAG, __VA_ARGS__)
#define ELOGD(...) __android_log_print(ANDROID_LOG_DEBUG, EVT_TAG, __VA_ARGS__)

// ─────────────────────────────────────────────────────────────────────────────
// LibusbEventThread — private constructor
// ─────────────────────────────────────────────────────────────────────────────

LibusbEventThread::LibusbEventThread(libusb_context *ctx) noexcept
    : ctx_(ctx)
{
    // All atomics are value-initialised in the member initialisers.
    // thread_ default-constructs to a non-joinable state; it is started in
    // create() after all other members are fully initialised.
}

// ─────────────────────────────────────────────────────────────────────────────
// LibusbEventThread::create — factory
// ─────────────────────────────────────────────────────────────────────────────

std::unique_ptr<LibusbEventThread> LibusbEventThread::create(
        libusb_context *ctx,
        std::string    *error_out)
{
    auto fail = [&](const char *msg) -> std::unique_ptr<LibusbEventThread> {
        ELOGE("LibusbEventThread::create: %s", msg);
        if (error_out) *error_out = msg;
        return nullptr;
    };

    if (ctx == nullptr)
        return fail("libusb_context is null");

    // Construct before starting the thread so the thread function can safely
    // reference all member variables from the moment it executes.
    auto *raw = new (std::nothrow) LibusbEventThread(ctx);
    if (raw == nullptr)
        return fail("out of memory allocating LibusbEventThread");

    std::unique_ptr<LibusbEventThread> evt(raw);

    // Signal the loop to run before creating the thread.  If thread creation
    // fails we reset it; the thread function reads this on its first iteration.
    evt->keep_running_.store(true, std::memory_order_relaxed);
    evt->running_.store(true,      std::memory_order_relaxed);

    // Start the dedicated event thread.
    // std::thread constructor throws std::system_error on OS-level failure
    // (e.g., EAGAIN: per-process thread limit, extremely rare on Android).
    try {
        evt->thread_ = std::thread(&LibusbEventThread::thread_fn, evt.get());
    } catch (const std::system_error &ex) {
        // Reset the flags so the object is in a "not running" state even
        // though the thread was never created.
        evt->keep_running_.store(false, std::memory_order_relaxed);
        evt->running_.store(false,      std::memory_order_relaxed);
        ELOGE("LibusbEventThread::create: std::thread ctor failed: %s", ex.what());
        if (error_out) *error_out = ex.what();
        return nullptr;
    } catch (...) {
        evt->keep_running_.store(false, std::memory_order_relaxed);
        evt->running_.store(false,      std::memory_order_relaxed);
        return fail("unknown failure creating libusb event thread");
    }

    const auto thread_id = std::hash<std::thread::id>{}(evt->thread_.get_id());
    ELOGI("LibusbEventThread::create: event thread started id=%zu", thread_id);

    return evt;
}

// ─────────────────────────────────────────────────────────────────────────────
// LibusbEventThread::stop
// ─────────────────────────────────────────────────────────────────────────────

void LibusbEventThread::stop() noexcept
{
    // Guard: calling stop() from the event thread itself would deadlock on join.
    // This should never happen in correctly written caller code, but catch it
    // in debug builds immediately rather than hanging the process.
    if (thread_.joinable() &&
        std::this_thread::get_id() == thread_.get_id())
    {
        // Log without __android_log_print format args to avoid heap allocation.
        __android_log_write(ANDROID_LOG_ERROR, EVT_TAG,
                            "stop() called from the event thread itself — "
                            "deadlock prevented; call stop() from another thread");
        return;
    }

    // ── Step 1: signal the loop to exit ──────────────────────────────────────
    // memory_order_release: all prior stores (e.g., pool teardown writes) are
    // visible to the event thread when it loads keep_running_ with acquire.
    keep_running_.store(false, std::memory_order_release);

    // ── Step 2: wake the thread out of its current poll immediately ───────────
    // libusb_interrupt_event_handler() causes libusb_handle_events_timeout_completed()
    // to return LIBUSB_ERROR_INTERRUPTED on the next (or current) call.
    // This reduces stop() latency from up to kPollTimeoutUs (100 ms) to < 1 ms.
    //
    // Available since libusb 1.0.21 (LIBUSB_API_VERSION >= 0x01000105).
    // Guard against older prebuilts to avoid a linker error.
#if defined(LIBUSB_API_VERSION) && (LIBUSB_API_VERSION >= 0x01000105)
    if (ctx_ != nullptr) {
        libusb_interrupt_event_handler(ctx_);
    }
#endif

    // ── Step 3: join — wait for thread_fn to return ───────────────────────────
    // thread_.joinable() is false if:
    //   a) The thread was never started (factory failed before thread_ was set).
    //   b) stop() was already called and the thread was already joined.
    // Both cases are safe no-ops.
    if (thread_.joinable()) {
        thread_.join();
        ELOGI("LibusbEventThread::stop: event thread joined successfully");
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// LibusbEventThread — destructor
// ─────────────────────────────────────────────────────────────────────────────

LibusbEventThread::~LibusbEventThread() noexcept
{
    // stop() is idempotent — safe to call even if the thread was already
    // joined by an explicit stop() call before destruction.
    stop();
}

// ─────────────────────────────────────────────────────────────────────────────
// LibusbEventThread::thread_fn — event loop body
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The dedicated libusb event processing loop.
 *
 * ### Startup sequence
 *
 *  1. Set the thread name to "usb_ev" (15-char Android limit) so the thread is
 *     identifiable in the Android Studio CPU Profiler and `adb shell ps -T`.
 *
 *  2. Attempt to elevate to SCHED_FIFO real-time scheduling at the minimum RT
 *     priority.  This is best-effort: failure (EPERM from SELinux on stock
 *     Android) is logged at WARN and execution continues at normal priority.
 *     On DAC-connected devices where the app has audio focus, this generally
 *     succeeds.
 *
 * ### Event loop
 *
 *   while (keep_running_) {
 *     rc = libusb_handle_events_timeout_completed(ctx_, &tv, nullptr);
 *     dispatch rc:
 *       LIBUSB_SUCCESS          → normal: iso callback was processed.
 *       LIBUSB_ERROR_INTERRUPTED → woken by interrupt_event_handler or stop() —
 *                                  re-check keep_running_ and proceed.
 *       any other error         → transient I/O error: increment error_count_.
 *                                  After kMaxConsecutiveErrors, self-terminate.
 *   }
 *
 * ### Consecutive-error guard
 *
 * Without this guard, a wedged USB host controller that returns LIBUSB_ERROR_IO
 * on every call would pin the thread at 100% CPU in a tight 1 µs loop.
 * After kMaxConsecutiveErrors (8) consecutive non-INTERRUPTED errors the loop
 * breaks autonomously.  Active playback produces a COMPLETED callback (SUCCESS)
 * every 1 ms, which resets the consecutive counter.
 *
 * ### Shutdown
 *
 * When `stop()` sets `keep_running_ = false`:
 *   • If the thread is blocked inside libusb_handle_events_timeout_completed(),
 *     libusb_interrupt_event_handler() causes an immediate LIBUSB_ERROR_INTERRUPTED
 *     return.  The loop re-checks keep_running_ (now false) and exits.
 *   • If the thread is between iterations (checking the condition), it exits
 *     on the next iteration guard check without entering libusb.
 *
 * In both cases `running_` is set to `false` with memory_order_release before
 * thread_fn returns, making the transition visible to `is_running()`.
 */
void LibusbEventThread::thread_fn() noexcept
{
    // ── 1. Name the thread ────────────────────────────────────────────────────
    // Android's /proc/<pid>/task/<tid>/comm is truncated at 15 characters.
    // "usb_ev" is readable and unambiguous in the CPU profiler.
    pthread_setname_np(pthread_self(), "usb_ev");

    ELOGI("event thread started  tid=%d  keep_running=%d",
          static_cast<int>(gettid()),
          keep_running_.load(std::memory_order_relaxed) ? 1 : 0);

    // ── 2. Best-effort real-time priority elevation ───────────────────────────
    // Ask for the lowest available SCHED_FIFO priority (just above CFS normal).
    // Higher priorities are reserved for the kernel USB interrupt handler and
    // the Android audio mixer (both at SCHED_FIFO/prio >= 2).
    //
    // On most stock Android devices this returns EPERM (SELinux denies
    // SCHED_FIFO for app-level processes) — continue at default priority.
    // On devices where the app holds MODIFY_AUDIO_SETTINGS or on rooted builds
    // the call succeeds, gaining immunity from UI-thread preemption.
    {
        struct sched_param sp{};
        sp.sched_priority = sched_get_priority_min(SCHED_FIFO) + 1;
        if (pthread_setschedparam(pthread_self(), SCHED_FIFO, &sp) != 0) {
            ELOGW("SCHED_FIFO elevation denied (errno=%d %s) — "
                  "continuing at default CFS priority",
                  errno, strerror(errno));
        } else {
            ELOGI("SCHED_FIFO priority=%d granted", sp.sched_priority);
        }
    }

    // ── 3. Event loop ─────────────────────────────────────────────────────────
    // Poll timeout: {seconds=0, microseconds=100'000} = 100 ms.
    // During active isochronous playback the function returns almost immediately
    // on every transfer completion (~every 1 ms), so the 100 ms limit is only
    // reached when there are no pending events (idle state or teardown).
    struct timeval poll_timeout{};
    poll_timeout.tv_sec  = 0L;
    poll_timeout.tv_usec = kPollTimeoutUs;

    uint32_t consecutive_errors = 0;

    // memory_order_acquire on keep_running_: pairs with the release-store in
    // stop(), ensuring we observe all memory writes made before stop() returns.
    while (keep_running_.load(std::memory_order_acquire)) {

        // libusb_handle_events_timeout_completed() — the central libusb pump.
        //
        // Parameters:
        //   ctx_         — the context whose transfers/events to service.
        //   &poll_timeout — block for at most kPollTimeoutUs if no events.
        //   nullptr       — completed: not using the per-transfer wait pattern;
        //                   we manage shutdown via keep_running_ instead.
        //
        // Returns:
        //   LIBUSB_SUCCESS          — one or more events processed normally.
        //   LIBUSB_ERROR_INTERRUPTED — woken by libusb_interrupt_event_handler.
        //   <other negative codes>  — I/O or system error.
        const int rc = libusb_handle_events_timeout_completed(
                ctx_, &poll_timeout, nullptr);

        if (__builtin_expect(rc == LIBUSB_SUCCESS, 1)) {
            // ── Hot path: event processed normally ────────────────────────────
            // iso_transfer_callback was called by libusb during this event tick.
            // Reset the consecutive-error counter — the bus is healthy.
            consecutive_errors = 0;
            continue;
        }

        if (rc == LIBUSB_ERROR_INTERRUPTED) {
            // ── Planned interruption ──────────────────────────────────────────
            // libusb_interrupt_event_handler() was called (from stop()).
            // Re-evaluate keep_running_ at the top of the loop; if it is now
            // false, the loop exits cleanly.  Log at DEBUG to avoid log spam
            // during normal teardown (this fires exactly once per stop()).
            ELOGD("event loop interrupted (planned wakeup)");
            consecutive_errors = 0;
            continue;
        }

        // ── Non-fatal error path ──────────────────────────────────────────────
        // LIBUSB_ERROR_IO (-1) is the most common: a transient USB bus fault,
        // a short packet, or a host controller hiccup.  A single error does not
        // indicate driver failure; log at WARN and allow one retry.
        //
        // We do NOT call __android_log_print every iteration to avoid saturating
        // logcat — log only on the first error in a consecutive run.
        if (consecutive_errors == 0) {
            ELOGW("libusb_handle_events_timeout_completed error: %s (%d)",
                  libusb_error_name(rc), rc);
        }

        ++consecutive_errors;
        error_count_.fetch_add(1, std::memory_order_relaxed);

        if (consecutive_errors >= kMaxConsecutiveErrors) {
            // ── Consecutive-error self-termination ────────────────────────────
            // A sustained stream of errors (all at ~1 µs poll round-trip)
            // indicates the USB host controller is wedged or the context has
            // been destroyed under us.  Pin CPU at 100% would be unacceptable;
            // self-terminate and let the higher-level driver detect is_running()
            // transitioning to false.
            ELOGE("event loop: %u consecutive errors — self-terminating "
                  "(last error: %s (%d))",
                  consecutive_errors, libusb_error_name(rc), rc);
            break;
        }
    }

    // ── 4. Thread exit ────────────────────────────────────────────────────────
    // Signal to external observers (is_running()) that the thread is done.
    // memory_order_release: all preceding stores in this function are visible
    // to any thread that subsequently loads running_ with acquire.
    running_.store(false, std::memory_order_release);

    ELOGI("event thread exited cleanly  tid=%d  total_errors=%u",
          static_cast<int>(gettid()),
          error_count_.load(std::memory_order_relaxed));
}
