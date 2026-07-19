// ─────────────────────────────────────────────────────────────────────────────
// dsd_playback_manager.h
//
// Step 13 — Automatic Native DSD → DoP fallback for UAC2 playback.
//
// Manages the full lifecycle of a DSD playback session with automatic mode
// switching:
//
//   1. Attempts to stream in Native DSD mode (FORMAT_TYPE_IV or TYPE_I RAW_DATA)
//      by submitting isochronous transfers on the native DSD alt setting.
//
//   2. Arms a 200 ms observation window.  During this window,
//      iso_transfer_callback notifies the pool if a LIBUSB_TRANSFER_STALL fires
//      (see usb_iso_transfer_pool.h § DSD stall observation window).
//
//   3. A dedicated C++ monitor thread polls the stall flag every 5 ms:
//        • Stall or submit failure detected   → execute_fallback_to_dop()
//        • 200 ms elapses without incident    → Native DSD declared stable
//
//   4. execute_fallback_to_dop() performs the sequence:
//        Phase A — Stop/join the sole producer; signal transfer shutdown.
//        Phase B — Wait for in-flight transfers to drain (bounded 500 ms).
//        Phase C — Allocate a DoP-rate pool for the PCM endpoint and detach ring.
//        Phase D — Clear the SPSC ring buffer (discard stale Native DSD bytes).
//        Phase E — Activate the PCM / DoP alt setting via SET_INTERFACE.
//        Phase F — Pre-fill the ring with 100 ms of DoP-formatted DSD silence
//                  (0x69 pattern — standard DSD silence) to prevent clicks.
//        Phase G — Replace the drained pool, submit it, and restart the producer.
//        Phase H — Notify the Kotlin layer via onEngineModeChanged(1).
//
// ── DSD Silence (0x69 pattern) ────────────────────────────────────────────────
//
// The DSD silence pattern 0x69 = 0b01101001 encodes a ~350 kHz dither tone
// at DSD64 (2.8224 MHz) which is well above the audible spectrum and causes
// no acoustic artefact.  Sending PCM zeros (0x00) to a DAC in DoP mode would
// be interpreted as an invalid DoP marker (0x00 ≠ 0x05/0xFA), potentially
// causing a pop or mute.  The 0x69 pattern, when framed by format_dop_stereo()
// with correct markers, produces valid DoP silence that the DAC handles cleanly.
//
// ── Thread model ──────────────────────────────────────────────────────────────
//
//   JNI init thread  → calls create(), arm_observation_window(), notify_*()
//   libusb event thread → calls iso_transfer_callback() → sets stall flag
//   DSD monitor thread → polls flag, executes fallback, calls Java callback
//
// execute_fallback_to_dop() runs on the monitor thread, which:
//   • Is NOT the libusb event thread (safe to call synchronous USB control xfers)
//   • Attaches to the JVM as a daemon thread to call onEngineModeChanged
//   • Calls teardown reset helpers that manipulate the UsbDriverContext fields
//
// ── Lifetime contract ─────────────────────────────────────────────────────────
//
//   The DsdPlaybackManager is owned by UsbDriverContext via unique_ptr.
//   It must be destroyed BEFORE teardown_context() — the destructor joins the
//   monitor thread, so no outstanding thread access the context after delete.
//   nativeRelease() destroys the manager before calling teardown_context();
//   Kotlin may request that reset earlier but correctness does not depend on it.
//
// ─────────────────────────────────────────────────────────────────────────────

#pragma once

#include <atomic>
#include <cstdint>
#include <memory>
#include <thread>

#include <jni.h>

#include "uac2_dsd_detector.h"       // Uac2DsdCapabilitySummary, DsdTransport

// Forward declaration — full type in usb_driver_context.h.
// Avoids a circular include: usb_driver_context.h → dsd_playback_manager.h.
struct UsbDriverContext;

// ─────────────────────────────────────────────────────────────────────────────
// DsdEngineMode
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Active DSD playback transport mode, mirrored as the `mode` argument to
 * the Kotlin `onEngineModeChanged(int mode)` callback.
 *
 * Ordered so that the integer value passed to Java matches the Kotlin
 * DsdEngineMode enum ordinal (NativeDsd = 0, DoP = 1).
 */
enum class DsdEngineMode : int32_t {
    /**
     * Native DSD bitstream (FORMAT_TYPE_IV or TYPE_I RAW_DATA).
     * No markers, no zero-padding — every USB byte is DSD data.
     * Reported to Java as mode = 0.
     */
    NativeDsd = 0,

    /**
     * DSD-over-PCM (DoP 1.1 framing with alternating 0x05/0xFA markers).
     * Active either initially (device has no native DSD alt setting) or
     * after automatic fallback from a failed Native DSD attempt.
     * Reported to Java as mode = 1.
     */
    DoP = 1,
};

// ─────────────────────────────────────────────────────────────────────────────
// DsdPlaybackManager
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Orchestrates Native DSD streaming with automatic DoP fallback.
 *
 * ### Lifecycle
 *
 *  1. Create only when validated Native DSD and DoP targets both exist.
 *  2. After transfers are submitted and the real producer is attached,
 *     call `arm_observation_window()` to start the 200 ms monitoring phase.
 *  3. If the DAC stalls, the monitor thread calls `execute_fallback_to_dop()`
 *     autonomously and notifies Kotlin via `onEngineModeChanged(1)`.
 *  4. Destroy by resetting `UsbDriverContext::dsd_manager` **before** calling
 *     `teardown_context()`.  The destructor joins the monitor thread.
 *
 * @see UsbDriverContext
 * @see IsoTransferPool::begin_dsd_observation_window
 * @see execute_fallback_to_dop
 */
class DsdPlaybackManager {
public:
    /**
     * Allocate and initialise a DsdPlaybackManager.
     *
     * Stores global JNI references to `listener` and the `onEngineModeChanged`
     * method so they can be called from the monitor thread without requiring
     * an active JNI frame on the calling thread.
     *
     * @param ctx                   Non-null pointer to the owning driver context.
     * @param capability            DSD capability summary from Step 11 detection.
     * @param vm                    JVM pointer for cross-thread JNI attachment.
     * @param listener              Kotlin object implementing `onEngineModeChanged`.
     *                              A global JNI reference is retained and released
     *                              in the destructor.
     * @param on_mode_changed_id    Cached jmethodID for `onEngineModeChanged(int)`.
     * @return                      Owning unique_ptr or nullptr on allocation failure.
     */
    [[nodiscard]] static std::unique_ptr<DsdPlaybackManager> create(
            UsbDriverContext             *ctx,
            const Uac2DsdCapabilitySummary &capability,
            JavaVM                       *vm,
            jobject                       listener,
            jmethodID                     on_mode_changed_id) noexcept;

    /**
     * Destructor — joins the monitor thread and releases the global JNI reference.
     *
     * Must be called on a thread that is NOT the libusb event thread or the
     * monitor thread itself.  The Kotlin layer must destroy the manager before
     * calling `nativeRelease()` / `teardown_context()`.
     */
    ~DsdPlaybackManager();

    // Non-copyable, non-movable (owns a thread and a global JNI ref).
    DsdPlaybackManager(const DsdPlaybackManager &)            = delete;
    DsdPlaybackManager &operator=(const DsdPlaybackManager &) = delete;
    DsdPlaybackManager(DsdPlaybackManager &&)                 = delete;
    DsdPlaybackManager &operator=(DsdPlaybackManager &&)      = delete;

    // ── Control API ───────────────────────────────────────────────────────────

    /**
     * Arm the 200 ms stall-detection window and start the monitor thread.
     *
     * Must be called after Native DSD transfers have been submitted and the
     * decoder producer is running; intentional bootstrap silence must not
     * consume the observation window.
     *
     * The monitor thread starts immediately and polls for failures every 5 ms
     * until either a stall/failure is detected or the 200 ms window expires.
     */
    [[nodiscard]] bool arm_observation_window() noexcept;

    /**
     * Signal that a `libusb_submit_transfer()` call failed during startup.
     *
     * Called from `nativeStartDsdPlayback` if any initial submission returns
     * a non-SUCCESS error code.  Sets an internal flag that causes the monitor
     * thread (once started by `arm_observation_window()`) to execute fallback
     * immediately, skipping the 200 ms window.
     *
     * Safe to call from any thread; uses memory_order_release.
     */
    void notify_submit_failure() noexcept;

    // ── Query API ─────────────────────────────────────────────────────────────

    /**
     * Return the currently active DSD transport mode.
     *
     * Thread-safe; loads with memory_order_acquire.
     * Transitions from NativeDsd to DoP are only made by the monitor thread.
     *
     * @return  DsdEngineMode::NativeDsd during the observation window and after
     *          stable Native DSD confirmation.
     *          DsdEngineMode::DoP after a successful fallback.
     */
    [[nodiscard]] DsdEngineMode active_mode() const noexcept {
        return active_mode_.load(std::memory_order_acquire);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Constants
    // ─────────────────────────────────────────────────────────────────────────

    /// Duration of the Native DSD stall observation window.
    static constexpr int kObservationWindowMs = 200;

    /// Monitor thread poll interval while the observation window is active.
    static constexpr int kPollIntervalMs = 5;

    /// DSD silence byte — standard DSD dither pattern, inaudible above ~350 kHz.
    /// Used to pre-fill the ring buffer during the Native→DoP transition.
    static constexpr uint8_t kDsdSilenceByte = 0x69u;

    /// Logcat tag for DSD manager messages.
    static constexpr const char *DSD_MGR_TAG = "DsdPlaybackMgr";

private:
    /**
     * Private constructor — use create() for validated construction.
     *
     * @param ctx                  Non-null driver context.
     * @param capability           DSD capability summary.
     * @param vm                   JVM pointer for cross-thread JNI.
     * @param listener_global      Global JNI reference to the Kotlin listener.
     * @param on_mode_changed_id   jmethodID for onEngineModeChanged(int).
     */
    DsdPlaybackManager(UsbDriverContext             *ctx,
                       const Uac2DsdCapabilitySummary &capability,
                       JavaVM                       *vm,
                       jobject                       listener_global,
                       jmethodID                     on_mode_changed_id) noexcept;

    /**
     * Monitor thread entry point.
     *
     * Polls for stall/failure flags every `kPollIntervalMs` milliseconds
     * until one of the following occurs:
     *   • `submit_failed_` is set        → immediate fallback
     *   • Pool stall flag is set         → fallback
     *   • `kObservationWindowMs` elapsed → Native DSD declared stable
     *   • `should_stop_monitor_` is set  → clean exit (destructor path)
     *
     * Starts only after `arm_observation_window()` sets `observation_armed_`.
     */
    void monitor_thread_fn() noexcept;

    /**
     * Execute the multi-phase Native DSD → DoP fallback sequence.
     *
     * Must be called on the monitor thread, NOT on the libusb event thread.
     * Handles its own JNI attachment/detachment for the final Java callback.
     *
     * ### Phase sequence
     *
     *  A. Set all TransferSlot::shutdown = true.
     *  B. Wait for all TransferSlot::in_flight to clear (≤ 500 ms).
     *  C. Detach ring buffer from pool (output silence automatically).
     *  D. Reset the SPSC ring buffer.
     *  E. Switch USB interface to PCM/DoP alt setting via SET_INTERFACE.
     *  F. Pre-fill ring with DoP-formatted DSD silence (0x69 pattern).
     *  G. Re-attach ring buffer; re-submit all transfer slots.
     *  H. Notify Kotlin: onEngineModeChanged(DsdEngineMode::DoP = 1).
     */
    void execute_fallback_to_dop() noexcept;

    /**
     * Pre-fill the SPSC ring buffer with DoP-framed DSD silence.
     *
     * Formats the DSD silence byte (0x69) into valid DoP 1.1 stereo frames
     * using format_dop_stereo() so every byte the DAC receives is correctly
     * marker-framed (no risk of a DoP marker mismatch producing a pop).
     *
     * Pushes as many complete 2 KB chunks as fit in half the ring's capacity,
     * capped at a practical maximum to bound latency before real audio resumes.
     *
     * @param target_bytes  Maximum bytes of DoP silence to push into the ring.
     *                      Clamped to min(target_bytes, ring->free_space()).
     */
    void push_dop_silence(std::size_t target_bytes) noexcept;

    /**
     * Notify the Kotlin listener of a DSD mode change.
     *
     * Attaches the calling thread (monitor thread) to the JVM as a daemon,
     * invokes `listener.onEngineModeChanged(mode)`, and detaches.
     *
     * @param mode  The new active engine mode (casted to jint for Java).
     */
    void notify_java(DsdEngineMode mode) noexcept;

    // ── Members ───────────────────────────────────────────────────────────────

    /// Non-owning pointer to the shared driver context.  Lives for the duration
    /// of the DsdPlaybackManager's lifetime (context outlives manager).
    UsbDriverContext *ctx_;

    /// DSD capability summary from Step 11, providing alt setting indices
    /// for both native DSD and PCM/DoP endpoints.
    Uac2DsdCapabilitySummary capability_;

    /// JVM pointer for attaching the monitor thread when calling Java.
    JavaVM *java_vm_;

    /// Global JNI reference to the Kotlin `onEngineModeChanged` listener.
    /// Created from the local ref passed to create(); deleted in the destructor.
    jobject listener_global_ref_;

    /// Cached jmethodID for `listener.onEngineModeChanged(int)`.
    jmethodID on_mode_changed_id_;

    /// Current engine mode — transitions from NativeDsd to DoP at most once.
    std::atomic<DsdEngineMode> active_mode_{DsdEngineMode::NativeDsd};

    /// Set by notify_submit_failure() to trigger immediate fallback.
    std::atomic<bool> submit_failed_{false};

    /// Set by the monitor thread when it is ready to begin polling.
    std::atomic<bool> observation_armed_{false};

    /// Set by the destructor to tell the monitor thread to exit cleanly.
    std::atomic<bool> should_stop_{false};

    /// Dedicated monitor thread.  Started in arm_observation_window(),
    /// joined in the destructor.
    std::thread monitor_thread_;
};
