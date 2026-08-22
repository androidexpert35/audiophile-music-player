// ─────────────────────────────────────────────────────────────────────────────
// dsd_playback_manager.cpp
//
// Step 13 — Implementation of the Native DSD → DoP automatic fallback manager.
//
// See dsd_playback_manager.h for the full design, phase sequence, thread model,
// and DSD silence rationale.
// ─────────────────────────────────────────────────────────────────────────────

#include "dsd_playback_manager.h"

#include <algorithm>   // std::min
#include <chrono>
#include <cstring>     // memset
#include <limits>
#include <system_error>
#include <thread>

#include <android/log.h>

// Full context type needed here to access transfer_pool, ring_buffer, etc.
#include "usb_driver_context.h"

// libusb for SET_INTERFACE control transfer and cancel.
#include "libusb/libusb.h"

// DoP formatter — used to produce valid DoP silence during Phase F.
#include "dop_formatter.h"
#include "uac2_clock_control.h"

// ── Logging helpers ───────────────────────────────────────────────────────────

#define DMGRD(...) __android_log_print(ANDROID_LOG_DEBUG, DsdPlaybackManager::DSD_MGR_TAG, __VA_ARGS__)
#define DMGRI(...) __android_log_print(ANDROID_LOG_INFO,  DsdPlaybackManager::DSD_MGR_TAG, __VA_ARGS__)
#define DMGRW(...) __android_log_print(ANDROID_LOG_WARN,  DsdPlaybackManager::DSD_MGR_TAG, __VA_ARGS__)
#define DMGRE(...) __android_log_print(ANDROID_LOG_ERROR, DsdPlaybackManager::DSD_MGR_TAG, __VA_ARGS__)

// ─────────────────────────────────────────────────────────────────────────────
// Factory
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Allocate a DsdPlaybackManager and retain a global JNI reference to the listener.
 *
 * Requires an active JNI environment on the calling thread (the JNI init thread)
 * in order to call env->NewGlobalRef(listener).
 *
 * @param ctx                  Driver context.  Must remain valid until manager is destroyed.
 * @param capability           DSD capability from Step 11.
 * @param vm                   JVM pointer for cross-thread attachment in the monitor thread.
 * @param listener             Kotlin listener object; stored as a global JNI reference.
 * @param on_mode_changed_id   Method ID for onEngineModeChanged(int).
 * @return                     Owning unique_ptr or nullptr on OOM.
 */
std::unique_ptr<DsdPlaybackManager> DsdPlaybackManager::create(
        UsbDriverContext               *ctx,
        const Uac2DsdCapabilitySummary &capability,
        JavaVM                         *vm,
        jobject                         listener,
        jmethodID                       on_mode_changed_id) noexcept
{
    if (!ctx || !vm || !listener || !on_mode_changed_id) {
        DMGRE("create: one or more required parameters are null");
        return nullptr;
    }

    // Obtain a JNIEnv* from the JVM to create the global ref.
    // The calling thread (JNI init thread) is already attached, so
    // GetEnv returns JNI_OK without needing an attach/detach cycle.
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK || !env) {
        DMGRE("create: GetEnv failed — listener global ref cannot be created");
        return nullptr;
    }

    // Upgrade the local reference to a global reference so the monitor thread
    // can use it after the calling JNI frame has been torn down.
    const jobject global_ref = env->NewGlobalRef(listener);
    if (!global_ref) {
        DMGRE("create: NewGlobalRef failed (out of JNI global reference slots?)");
        return nullptr;
    }

    auto *raw = new (std::nothrow)
        DsdPlaybackManager(ctx, capability, vm, global_ref, on_mode_changed_id);

    if (!raw) {
        env->DeleteGlobalRef(global_ref);
        DMGRE("create: out of memory allocating DsdPlaybackManager");
        return nullptr;
    }

    DMGRI("create: DsdPlaybackManager ready — native_dsd_alt=%u  pcm_alt=%u",
          capability.native_dsd_alt_setting,
          capability.pcm_alt_setting);

    return std::unique_ptr<DsdPlaybackManager>(raw);
}

// ─────────────────────────────────────────────────────────────────────────────
// Private constructor
// ─────────────────────────────────────────────────────────────────────────────

DsdPlaybackManager::DsdPlaybackManager(
        UsbDriverContext               *ctx,
        const Uac2DsdCapabilitySummary &capability,
        JavaVM                         *vm,
        jobject                         listener_global,
        jmethodID                       on_mode_changed_id) noexcept
    : ctx_(ctx),
      capability_(capability),
      java_vm_(vm),
      listener_global_ref_(listener_global),
      on_mode_changed_id_(on_mode_changed_id)
{}

// ─────────────────────────────────────────────────────────────────────────────
// Destructor
// ─────────────────────────────────────────────────────────────────────────────

DsdPlaybackManager::~DsdPlaybackManager()
{
    // Signal the monitor thread to stop if it is still running.
    should_stop_.store(true, std::memory_order_release);

    if (monitor_thread_.joinable()) {
        monitor_thread_.join();
        DMGRI("~DsdPlaybackManager: monitor thread joined");
    }

    // Release the global JNI reference.  A JNIEnv* is needed for this.
    JNIEnv *env = nullptr;
    if (java_vm_->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_OK && env) {
        env->DeleteGlobalRef(listener_global_ref_);
    } else {
        // A daemon thread that wasn't detached — attach briefly, release, detach.
        if (java_vm_->AttachCurrentThreadAsDaemon(&env, nullptr) == JNI_OK)
        {
            env->DeleteGlobalRef(listener_global_ref_);
            java_vm_->DetachCurrentThread();
        }
    }
    listener_global_ref_ = nullptr;

    DMGRI("~DsdPlaybackManager: resources released");
}

// ─────────────────────────────────────────────────────────────────────────────
// arm_observation_window
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Arm the 200 ms observation window and launch the background monitor thread.
 *
 * See dsd_playback_manager.h for the full monitoring protocol.
 */
bool DsdPlaybackManager::arm_observation_window() noexcept
{
    if (observation_armed_.exchange(true, std::memory_order_acq_rel)) {
        DMGRW("arm_observation_window: observation already armed");
        return true;
    }

    // Arm the pool-level timing window so iso_transfer_callback can record stalls.
    if (ctx_->transfer_pool) {
        ctx_->transfer_pool->begin_dsd_observation_window();
    }

    try {
        monitor_thread_ = std::thread(&DsdPlaybackManager::monitor_thread_fn, this);
    } catch (const std::system_error &error) {
        observation_armed_.store(false, std::memory_order_release);
        if (ctx_->transfer_pool) {
            ctx_->transfer_pool->clear_dsd_observation_window();
        }
        DMGRE("arm_observation_window: thread creation failed: %s", error.what());
        return false;
    } catch (...) {
        observation_armed_.store(false, std::memory_order_release);
        if (ctx_->transfer_pool) {
            ctx_->transfer_pool->clear_dsd_observation_window();
        }
        DMGRE("arm_observation_window: unknown thread creation failure");
        return false;
    }

    DMGRI("arm_observation_window: 200 ms Native DSD observation window started");
    return true;
}

// ─────────────────────────────────────────────────────────────────────────────
// notify_submit_failure
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Signal that an initial libusb_submit_transfer() failed.
 *
 * The monitor thread will trigger immediate fallback on its next 5 ms poll.
 */
void DsdPlaybackManager::notify_submit_failure() noexcept
{
    submit_failed_.store(true, std::memory_order_release);
    DMGRW("notify_submit_failure: initial transfer submit failed — "
          "DoP fallback will be triggered");
}

// ─────────────────────────────────────────────────────────────────────────────
// monitor_thread_fn
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Monitor thread entry point.
 *
 * Spins at 5 ms intervals watching for Native DSD failure signals.
 * On detection, delegates to execute_fallback_to_dop().
 * On clean 200 ms expiry, logs success and exits.
 */
void DsdPlaybackManager::monitor_thread_fn() noexcept
{
    using namespace std::chrono;

    DMGRI("monitor_thread_fn: started — polling for 200 ms");

    const auto window_start = steady_clock::now();
    const auto window_end   = window_start + milliseconds(kObservationWindowMs);

    while (!should_stop_.load(std::memory_order_acquire)) {
        // ── Check for immediate-fallback conditions ────────────────────────────
        // Priority 1 — submit failure (fires on next loop iteration after flag set)
        const bool submit_fail = submit_failed_.load(std::memory_order_acquire);

        // Priority 2 — endpoint stall detected inside the 200 ms window
        const bool pool_stall = ctx_->transfer_pool &&
                                ctx_->transfer_pool->is_dsd_stall_in_window();

        if (submit_fail || pool_stall) {
            if (submit_fail) {
                DMGRW("monitor_thread_fn: submit failure detected — executing DoP fallback");
            } else {
                DMGRW("monitor_thread_fn: STALL within 200 ms window — executing DoP fallback");
            }

            // Disarm the pool window before executing fallback so the callback
            // does not record additional stalls during the teardown.
            if (ctx_->transfer_pool) {
                ctx_->transfer_pool->clear_dsd_observation_window();
            }

            execute_fallback_to_dop();
            return;
        }

        // ── Check if the observation window has expired cleanly ────────────────
        const auto now = steady_clock::now();
        if (now >= window_end) {
            DMGRI("monitor_thread_fn: 200 ms observation window elapsed — "
                  "Native DSD declared stable ✅");
            if (ctx_->transfer_pool) {
                ctx_->transfer_pool->clear_dsd_observation_window();
            }
            return;
        }

        // ── Sleep 5 ms before next poll ───────────────────────────────────────
        // sleep_for with 5 ms is safe here: this thread is not on the hot audio
        // path.  std::this_thread::sleep_for doesn't block any audio callbacks.
        std::this_thread::sleep_for(milliseconds(kPollIntervalMs));
    }

    // should_stop_ was set (destructor path) — disarm window and exit cleanly.
    if (ctx_->transfer_pool) {
        ctx_->transfer_pool->clear_dsd_observation_window();
    }
    DMGRI("monitor_thread_fn: stopped by destructor signal");
}

// ─────────────────────────────────────────────────────────────────────────────
// execute_fallback_to_dop
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Perform the complete Native DSD → DoP fallback sequence.
 *
 * See dsd_playback_manager.h Phase A–H for the full step-by-step description.
 * This function runs on the monitor thread, which is safe for synchronous USB
 * control transfers (not the libusb callback thread).
 */
void DsdPlaybackManager::execute_fallback_to_dop() noexcept
{
    using namespace std::chrono;

    DMGRI("execute_fallback_to_dop: ═══ beginning Native DSD → DoP fallback ═══");

    if (!capability_.supports_dop || capability_.pcm_endpoint == 0U) {
        DMGRE("execute_fallback_to_dop: no valid DoP endpoint is available");
        (void)ctx_->playback_state.transition_to(UsbPlaybackState::Failed);
        return;
    }
    if (!ctx_->playback_state.transition_to(UsbPlaybackState::SwitchingToDop)) {
        DMGRE("execute_fallback_to_dop: illegal playback state transition");
        return;
    }
    if (!ctx_->producer_coordinator ||
        !ctx_->producer_coordinator->quiesce_for_dsd_switch(DsdWireMode::Dop)) {
        DMGRE("execute_fallback_to_dop: no producer available to quiesce");
        (void)ctx_->playback_state.transition_to(UsbPlaybackState::Failed);
        return;
    }

    // ── Phase A: Signal all transfer slots to stop resubmission ───────────────
    //
    // Setting shutdown = true prevents iso_transfer_callback from calling
    // libusb_submit_transfer() again.  Transfers that are currently in-flight
    // will complete (COMPLETED or CANCELLED); the callback will then clear
    // in_flight without re-submitting.
    if (ctx_->transfer_pool) {
        for (auto &slot : ctx_->transfer_pool->slots()) {
            slot.shutdown.store(true, std::memory_order_release);
        }
        DMGRD("execute_fallback_to_dop: Phase A — all slots marked shutdown");
    }

    // ── Phase B: Wait for all in-flight transfers to drain (bounded 500 ms) ───
    //
    // The libusb event thread still runs during this wait; it processes
    // completion callbacks which clear in_flight.  Once all in_flight flags are
    // false every callback has fired and no slot will touch its libusb_transfer
    // struct again.
    if (ctx_->transfer_pool) {
        const auto drain_deadline = steady_clock::now() + milliseconds(500);
        bool       all_drained    = false;

        while (steady_clock::now() < drain_deadline) {
            all_drained = true;
            for (const auto &slot : ctx_->transfer_pool->slots()) {
                if (slot.in_flight.load(std::memory_order_acquire)) {
                    all_drained = false;
                    break;
                }
            }
            if (all_drained) break;
            std::this_thread::sleep_for(milliseconds(1));
        }

        if (!all_drained) {
            DMGRE("execute_fallback_to_dop: Phase B — drain timeout; "
                  "aborting transition before ring reset");
            (void)ctx_->playback_state.transition_to(UsbPlaybackState::Failed);
            return;
        } else {
            DMGRD("execute_fallback_to_dop: Phase B — all transfers drained");
        }
    }

    if (!ctx_->transfer_pool) {
        DMGRE("execute_fallback_to_dop: transfer pool disappeared during fallback");
        (void)ctx_->playback_state.transition_to(UsbPlaybackState::Failed);
        return;
    }

    // Native DSD_U32 carries 32 one-bit samples per USB frame; DoP carries 16.
    // The DoP carrier therefore runs at exactly twice the Native DSD frame rate.
    const IsoTransferPoolConfig native_config = ctx_->transfer_pool->config();
    if (native_config.sample_rate_hz >
        std::numeric_limits<uint32_t>::max() / 2U) {
        DMGRE("execute_fallback_to_dop: Native DSD frame rate overflows DoP carrier");
        (void)ctx_->playback_state.transition_to(UsbPlaybackState::Failed);
        return;
    }

    IsoTransferPoolConfig dop_config = native_config;
    dop_config.sample_rate_hz = native_config.sample_rate_hz * 2U;
    dop_config.endpoint_address = capability_.pcm_endpoint;

    const uint64_t required_bytes_per_uframe =
        ((static_cast<uint64_t>(dop_config.sample_rate_hz) + 7'999ULL) / 8'000ULL) *
        dop_config.bytes_per_audio_frame;
    if (capability_.pcm_bandwidth != 0U &&
        required_bytes_per_uframe > capability_.pcm_bandwidth) {
        DMGRE("execute_fallback_to_dop: DoP needs %llu B/uframe but endpoint "
              "0x%02X exposes only %u",
              static_cast<unsigned long long>(required_bytes_per_uframe),
              capability_.pcm_endpoint,
              capability_.pcm_bandwidth);
        (void)ctx_->playback_state.transition_to(UsbPlaybackState::Failed);
        return;
    }

    std::string pool_error;
    auto dop_pool = IsoTransferPool::create(
            ctx_->device_handle, dop_config, &pool_error);
    if (!dop_pool) {
        DMGRE("execute_fallback_to_dop: cannot allocate DoP transfer pool: %s",
              pool_error.c_str());
        (void)ctx_->playback_state.transition_to(UsbPlaybackState::Failed);
        return;
    }

    // ── Phase C: Detach ring buffer from pool ─────────────────────────────────
    //
    // The pool's callback outputs zero-filled silence (from memset) when the
    // ring pointer is null.  This keeps the DAC's isochronous clock alive and
    // avoids a pop while the alt setting is being switched.
    if (ctx_->transfer_pool) {
        ctx_->transfer_pool->attach_ring_buffer(nullptr);
        DMGRD("execute_fallback_to_dop: Phase C — ring buffer detached (silence output)");
    }

    // ── Phase D: Clear the SPSC ring buffer ───────────────────────────────────
    //
    // Discard any stale Native DSD bytes that the producer may have pushed.
    // These bytes would be invalid if re-played through the DoP formatter.
    // SpscRingBuffer::reset() is only safe when no producer is active; Phase A
    // synchronously stopped and joined the sole producer through the coordinator.
    if (ctx_->ring_buffer) {
        ctx_->ring_buffer->reset();
        DMGRD("execute_fallback_to_dop: Phase D — ring buffer reset (stale DSD data cleared)");
    }

    // ── Phase E: Activate the PCM / DoP alt setting via SET_INTERFACE ─────────
    //
    // libusb_set_interface_alt_setting() issues a USB SETUP+DATA+STATUS transfer
    // (synchronous on the calling thread).  This is safe here because:
    //   a) The libusb event thread is still running (required for the control xfer).
    //   b) The monitor thread is not the event thread.
    //
    // The PCM alt setting index comes from the Uac2DsdCapabilitySummary produced
    // by uac2_detect_native_dsd() in Step 11.  It was already validated there.
    if (ctx_->device_handle && capability_.supports_dop) {
        const int iface = static_cast<int>(capability_.pcm_interface);
        const int alt   = static_cast<int>(capability_.pcm_alt_setting);
        const int previous_iface = ctx_->claimed_interface_number;
        const bool changes_interface = previous_iface >= 0 && previous_iface != iface;

        DMGRI("execute_fallback_to_dop: Phase E — SET_INTERFACE iface=%d alt=%d (PCM/DoP)",
              iface, alt);

        if (changes_interface) {
            const int claim_rc = libusb_claim_interface(ctx_->device_handle, iface);
            if (claim_rc != LIBUSB_SUCCESS) {
                DMGRE("execute_fallback_to_dop: Phase E — claim PCM iface=%d "
                      "failed: %s (%d)",
                      iface, libusb_error_name(claim_rc), claim_rc);
                (void)ctx_->playback_state.transition_to(UsbPlaybackState::Failed);
                return;
            }
        }

        if (previous_iface >= 0) {
            const int idle_rc = libusb_set_interface_alt_setting(
                    ctx_->device_handle, previous_iface, 0);
            if (idle_rc != LIBUSB_SUCCESS) {
                DMGRE("execute_fallback_to_dop: Phase E — cannot idle old "
                      "iface=%d: %s (%d)",
                      previous_iface, libusb_error_name(idle_rc), idle_rc);
                if (changes_interface) {
                    (void)libusb_release_interface(ctx_->device_handle, iface);
                }
                (void)ctx_->playback_state.transition_to(UsbPlaybackState::Failed);
                return;
            }
        }

        const uint8_t clock_id = ctx_->resolved_clock_source_id;
        const uint8_t control_iface = static_cast<uint8_t>(
                ctx_->ctrl_interface_number >= 0
                    ? ctx_->ctrl_interface_number
                    : 0);
        const int clock_rc = uac2_set_clock_sample_rate(
                ctx_->device_handle,
                control_iface,
                clock_id,
                dop_config.sample_rate_hz);
        if (clock_rc != 4) {
            DMGRE("execute_fallback_to_dop: Phase E — DoP clock SET_CUR "
                  "%u Hz failed for clock=%u: %s (%d)",
                  dop_config.sample_rate_hz,
                  clock_id,
                  libusb_error_name(clock_rc),
                  clock_rc);
            if (changes_interface) {
                (void)libusb_release_interface(ctx_->device_handle, iface);
            }
            (void)ctx_->playback_state.transition_to(UsbPlaybackState::Failed);
            return;
        }
        // XMOS/Savitech firmware needs a short PLL settling window between
        // Clock Source SET_CUR and activation of the new streaming alt.
        std::this_thread::sleep_for(milliseconds(50));

        const int rc = libusb_set_interface_alt_setting(ctx_->device_handle, iface, alt);
        if (rc != LIBUSB_SUCCESS) {
            DMGRE("execute_fallback_to_dop: Phase E — SET_INTERFACE failed: %s (%d)",
                  libusb_error_name(rc), rc);
            if (changes_interface) {
                (void)libusb_release_interface(ctx_->device_handle, iface);
            }
            (void)ctx_->playback_state.transition_to(UsbPlaybackState::Failed);
            return;
        } else {
            if (changes_interface) {
                (void)libusb_release_interface(ctx_->device_handle, previous_iface);
            }
            ctx_->active_alt_setting       = alt;
            ctx_->claimed_interface_number = iface;
            ctx_->iso_endpoint_address     = capability_.pcm_endpoint;
            DMGRD("execute_fallback_to_dop: Phase E — alt setting activated, context updated");
        }
    } else if (!capability_.supports_dop) {
        DMGRE("execute_fallback_to_dop: Phase E — PCM/DoP alt setting unavailable");
        (void)ctx_->playback_state.transition_to(UsbPlaybackState::Failed);
        return;
    }

    ctx_->transfer_pool = std::move(dop_pool);
    ctx_->dsd_formatter = make_dsd_frame_formatter(false);
    ctx_->dsd_wire_mode = DsdWireMode::Dop;
    // No set_silence_byte() call here on purpose: this is a freshly created
    // pool, and a DoP stream idles correctly on the default kPcmSilenceByte
    // (a marker-less zero frame drops the DAC back to PCM, i.e. silence).
    // Only native DSD needs the 0x69 override — see nativeStartDsdPlayback.
    (void)ctx_->playback_state.transition_to(UsbPlaybackState::Priming);

    // ── Phase F: Pre-fill ring with DoP-framed DSD silence ────────────────────
    //
    // Push DoP-formatted silence into the ring before re-attaching it to the
    // pool.  This ensures the DAC receives valid DoP markers immediately upon
    // stream resumption and prevents a click from marker-less PCM bytes.
    //
    // Target: fill the ring to ~50 % capacity with silence.  At DSD64 DoP
    // (176,400 fps × 8 bytes/frame) this is ~50 ms of silence — more than
    // enough for the DAC's DoP detector to lock.
    if (ctx_->ring_buffer) {
        const std::size_t target = ctx_->ring_buffer->capacity() / 2u;
        push_dop_silence(target);
    }

    // ── Phase G: Re-attach ring buffer and re-submit all transfer slots ────────
    //
    // Reset slot state so callbacks will begin resubmitting normally.
    // Re-attach ring first so the first callbacks consume silence rather than
    // outputting PCM zeros.
    if (ctx_->transfer_pool && ctx_->ring_buffer) {
        ctx_->transfer_pool->attach_ring_buffer(ctx_->ring_buffer.get());
        DMGRD("execute_fallback_to_dop: Phase G — ring buffer re-attached");

        auto       &transfers = ctx_->transfer_pool->transfers();
        auto       &slots     = ctx_->transfer_pool->slots();
        uint32_t    submitted = 0;

        for (std::size_t i = 0; i < transfers.size(); ++i) {
            // Reset per-slot state for clean re-entry.
            slots[i].shutdown.store(false, std::memory_order_release);
            slots[i].consecutive_errors = 0;

            // Arm in_flight BEFORE submit so the callback can never see false
            // after we resubmit but before the in_flight flag is set.
            slots[i].in_flight.store(true, std::memory_order_release);

            const int rc = libusb_submit_transfer(transfers[i]);
            if (rc != LIBUSB_SUCCESS) {
                DMGRE("execute_fallback_to_dop: Phase G — slot[%zu] resubmit failed: "
                      "%s (%d) — slot left in shutdown state",
                      i, libusb_error_name(rc), rc);
                // Revert the flags so the callback sees a clean shutdown.
                slots[i].in_flight.store(false, std::memory_order_release);
                slots[i].shutdown.store(true,  std::memory_order_release);
            } else {
                ++submitted;
            }
        }

        DMGRI("execute_fallback_to_dop: Phase G — %u/%zu slot(s) resubmitted",
              submitted, transfers.size());
        if (submitted == 0U) {
            DMGRE("execute_fallback_to_dop: no DoP transfer could be submitted");
            (void)ctx_->playback_state.transition_to(UsbPlaybackState::Failed);
            return;
        }
    }

    if (!ctx_->producer_coordinator || !ctx_->producer_coordinator->resume()) {
        DMGRE("execute_fallback_to_dop: producer restart failed");
        (void)ctx_->playback_state.transition_to(UsbPlaybackState::Failed);
        return;
    }

    // ── Update active mode ─────────────────────────────────────────────────────
    active_mode_.store(DsdEngineMode::DoP, std::memory_order_release);
    (void)ctx_->playback_state.transition_to(UsbPlaybackState::StreamingDop);
    DMGRI("execute_fallback_to_dop: active_mode switched to DoP");

    // ── Phase H: Notify Kotlin via onEngineModeChanged(1) ─────────────────────
    notify_java(DsdEngineMode::DoP);

    DMGRI("execute_fallback_to_dop: ═══ fallback complete — streaming DoP ═══");
}

// ─────────────────────────────────────────────────────────────────────────────
// push_dop_silence
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Pre-fill the ring buffer with DoP-framed DSD silence (0x69 pattern).
 *
 * Allocates a stack-local 2 KB chunk (256 DoP frames × 8 bytes each),
 * formats the 0x69 DSD silence bytes through format_dop_stereo(), and pushes
 * the chunk repeatedly until `target_bytes` are pushed or the ring is full.
 *
 * The 256-frame chunk size is small enough to live on the stack safely and
 * large enough to push ~44 ms of silence per second at DSD128 rates.
 *
 * @param target_bytes  Maximum bytes to push; clamped to ring free space.
 */
void DsdPlaybackManager::push_dop_silence(std::size_t target_bytes) noexcept
{
    if (!ctx_->ring_buffer) return;

    // 256 DoP stereo frames → 256 × 2 bytes per channel input, 256 × 8 bytes output
    constexpr std::size_t kFrames    = 256u;
    constexpr std::size_t kInPerCh   = kFrames * 2u;    // 512 bytes per channel
    constexpr std::size_t kOutBytes  = kFrames * 8u;    // 2048 bytes output

    // Stack-allocated silence sources and output buffer.
    // 512 + 512 + 2048 = 3072 bytes stack usage — safe on Android audio threads
    // (default stack ≥ 32 KB for audio threads).
    uint8_t dsd_l[kInPerCh];
    uint8_t dsd_r[kInPerCh];
    uint8_t pcm_out[kOutBytes];

    // 0x69 = standard DSD silence dither tone (~350 kHz, well above audible range).
    memset(dsd_l, kDsdSilenceByte, kInPerCh);
    memset(dsd_r, kDsdSilenceByte, kInPerCh);

    // Chain the DoP marker state across chunks for correct 0x05/0xFA alternation.
    uint8_t     marker  = kDopMarkerA;
    std::size_t pushed  = 0;

    while (pushed < target_bytes) {
        const std::size_t free = ctx_->ring_buffer->free_space();
        if (free < kOutBytes) break;    // ring is sufficiently full

        marker = format_dop_stereo(dsd_l, dsd_r, pcm_out, kFrames, marker);

        if (!ctx_->ring_buffer->push(pcm_out, kOutBytes)) break;

        pushed += kOutBytes;
    }

    DMGRI("push_dop_silence: pushed %zu bytes of DoP silence (~%.1f ms at DSD64 DoP)",
          pushed,
          static_cast<double>(pushed) / (176400.0 * 8.0) * 1000.0);
}

// ─────────────────────────────────────────────────────────────────────────────
// notify_java
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Invoke `listener.onEngineModeChanged(mode)` on the Kotlin side.
 *
 * Attaches the calling thread (monitor thread) to the JVM as a daemon so
 * the call does not prevent JVM orderly shutdown.  Detaches before returning.
 *
 * @param mode  New engine mode to report.
 */
void DsdPlaybackManager::notify_java(DsdEngineMode mode) noexcept
{
    JNIEnv *env = nullptr;
    bool     should_detach = false;

    // Try to get the JNIEnv* for the calling thread.  The monitor thread is a
    // C++ std::thread not created by the JVM, so GetEnv returns JNI_EDETACHED.
    const jint get_ret =
        java_vm_->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);

    if (get_ret == JNI_EDETACHED) {
        // Attach as a daemon thread.  Daemon attachment does not prevent orderly
        // JVM shutdown (safe for background C++ threads).
        if (java_vm_->AttachCurrentThreadAsDaemon(&env, nullptr) != JNI_OK || !env)
        {
            DMGRE("notify_java: AttachCurrentThreadAsDaemon failed — "
                  "onEngineModeChanged will NOT be called");
            return;
        }
        should_detach = true;
    } else if (get_ret != JNI_OK || !env) {
        DMGRE("notify_java: GetEnv failed (ret=%d) — "
              "onEngineModeChanged will NOT be called", static_cast<int>(get_ret));
        return;
    }

    // Invoke the Kotlin callback.  The global ref and method ID are stable for
    // the lifetime of this object, so no additional null-guard is needed here.
    env->CallVoidMethod(listener_global_ref_,
                        on_mode_changed_id_,
                        static_cast<jint>(mode));

    if (env->ExceptionCheck()) {
        DMGRE("notify_java: onEngineModeChanged threw a Java exception — clearing");
        env->ExceptionDescribe();
        env->ExceptionClear();
    }

    if (should_detach) {
        java_vm_->DetachCurrentThread();
    }

    DMGRI("notify_java: onEngineModeChanged(%d) delivered successfully",
          static_cast<int>(mode));
}
