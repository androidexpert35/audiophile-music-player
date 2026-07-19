// ─────────────────────────────────────────────────────────────────────────────
// usb_teardown.cpp
//
// Step 10 — Safe teardown sequence for the user-space USB Audio Class 2.0 driver.
//
// Implements `teardown_context()` — the comprehensive cleanup function invoked
// when the user disconnects the DAC or closes the app.
//
// See usb_teardown.h for the full ordering rationale and the nine-phase table.
//
// ── Memory-ordering summary ───────────────────────────────────────────────────
//
// Phase 1 writes  TransferSlot::shutdown  with memory_order_release.
//   The callback reads it with memory_order_acquire — any writes visible before
//   the release are  visible to the callback when it observes shutdown == true.
//
// Phase 3 reads   TransferSlot::in_flight  with memory_order_acquire.
//   The callback clears it with  memory_order_release after it finishes all
//   buffer writes — the acquire on our side guarantees we see the fully
//   quiesced slot state before we proceed to Phase 4.
//
// Phase 4 calls   LibusbEventThread::stop() which:
//     a) stores keep_running_ = false  (memory_order_release)
//     b) calls libusb_interrupt_event_handler() to wake a blocked poll in < 1 ms
//     c) joins the thread              — acts as a full memory fence.
//   After join() all stores by thread_fn (including running_ = false) are
//   visible to the caller — the IsoTransferPool is safe to destroy in Phase 6.
//
// ─────────────────────────────────────────────────────────────────────────────

#include "usb_teardown.h"
#include "usb_driver_context.h"
#include "usb_device_controller.h"

#include <android/log.h>
#include <chrono>
#include <thread>       // std::this_thread::sleep_for
#include <unistd.h>     // usleep — DSD soft-reset 50 ms PLL stabilisation

#include "libusb/libusb.h"

// ─── Logging macros ───────────────────────────────────────────────────────────

static constexpr const char *TD_TAG = "UsbTeardown";

#define TDLOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TD_TAG, __VA_ARGS__)
#define TDLOGI(...) __android_log_print(ANDROID_LOG_INFO,  TD_TAG, __VA_ARGS__)
#define TDLOGW(...) __android_log_print(ANDROID_LOG_WARN,  TD_TAG, __VA_ARGS__)
#define TDLOGE(...) __android_log_print(ANDROID_LOG_ERROR, TD_TAG, __VA_ARGS__)

// ─── Teardown timing constants ────────────────────────────────────────────────

/**
 * Maximum wall-clock duration to wait for all CANCELLED callbacks to clear
 * their `TransferSlot::in_flight` flags (Phase 3 bounded-wait loop).
 *
 * Rationale:
 *   Under normal conditions a USB HS isochronous cancellation is acknowledged
 *   within 1–2 microframes (125–250 µs).  The 2-second ceiling covers:
 *     • A DAC physically unplugged mid-stream, where the kerne hardware
 *       cleans up without calling the callback — in_flight flags may already
 *       be false via the NO_DEVICE callback path, so the wait resolves instantly.
 *     • A wedged USB host controller SoC that never completes the cancellation.
 *       After 2 s we log an ERROR and proceed with forced teardown so the app
 *       does not hang indefinitely.
 */
static constexpr auto kCancellationTimeout = std::chrono::milliseconds(2000);

/**
 * Sleep interval between consecutive `in_flight` polls in Phase 3.
 *
 * 500 µs = 4 USB microframes — long enough to yield the CPU to the event thread
 * (which must process the CANCELLED events to clear `in_flight`), short enough
 * to keep teardown latency below a perceptible threshold for the user.
 */
static constexpr auto kCancellationPollInterval = std::chrono::microseconds(500);

// ─────────────────────────────────────────────────────────────────────────────
// dsd_xmos_soft_reset — Phase 6.5
//
// Forces an XMOS/FiiO DAC out of its DSD DSP lock before the streaming
// interface is released.
//
// Background
// ──────────
// The XMOS USB receiver chip keeps its internal DSD state machine and PLL
// locked even after the Linux host cancels all isochronous transfers and the
// transfer pool is torn down (Phases 1–6).  The chip is USB-powered and does
// NOT reset its audio DSP core when the interface claim is merely released —
// it only resets on USB bus reset or physical disconnect.
//
// Consequence: when the next session is PCM, the chip rejects:
//   • The UAC2 clock SET_CUR (still expecting a DSD clock rate)
//   • The isochronous PCM payload (still validating DSD framing)
// and displays "FSR ERROR" (Frame Sample Rate mismatch) with silent output.
//
// Soft-Reset sequence (all steps best-effort, no step is fatal)
// ─────────────────────────────────────────────────────────────
//   1. CLEAR_HALT            — remove ENDPOINT_HALT condition FIRST, before
//      sending SET_INTERFACE.  A halted endpoint suppresses Alt-0 state changes
//      in XMOS firmware, so Alt-0 must not be sent until the halt is cleared.
//   2. SET_INTERFACE alt=0   — quiesce DSD ISO endpoint and clear DMA FIFO
//      (succeeds now that the halt is gone).
//   3. SET_CUR 44100 Hz      — jam the UAC2 Clock Source back to a standard PCM
//      rate using ONLY the bClockID that was acknowledged during session setup
//      (ctx->resolved_clock_source_id).  Sending SET_CUR to an invalid clock
//      entity triggers LIBUSB_ERROR_PIPE (-9), which stalls EP0 and prevents
//      any subsequent transfers from succeeding — the root cause of the
//      persistent -9 failures seen after DSD playback.
//   4. 100 ms wait           — allow the PLL to complete its DSD→PCM re-lock
//      cycle before libusb_release_interface() is called.
//
// Placement
// ─────────
// Called from teardown_context() between Phase 6 (pool destroyed) and
// Phase 7 (interface released), while ctx->device_handle,
// ctx->claimed_interface_number, and ctx->ctrl_interface_number are all
// still valid.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Execute the XMOS Soft Reset sequence on a DSD-session context.
 *
 * All four steps are best-effort: a non-SUCCESS return from any step is logged
 * as a warning but does NOT abort the sequence.  Teardown always continues to
 * Phase 7 regardless of the outcome here.
 *
 * ### Step ordering rationale
 *
 * Step 1 (CLEAR_HALT) must precede Step 2 (Alt-0).  When the ISO OUT endpoint
 * is in the halted state, XMOS firmware silently discards the SET_INTERFACE
 * Alt-0 request, leaving the DSD clock domain active.  Clearing the halt first
 * ensures the Alt-0 state change reaches the DSD state machine.
 *
 * Step 3 (SET_CUR) uses `ctx->resolved_clock_source_id` — the bClockID that
 * was positively acknowledged during session setup — to avoid sending control
 * transfers to non-existent clock source entities.  Invalid entity IDs cause
 * `LIBUSB_ERROR_PIPE (-9)`, which stalls EP0 and prevents the subsequent PCM
 * session's clock programming from succeeding.
 *
 * @param ctx  Non-null teardown context.  `device_handle`,
 *             `claimed_interface_number`, `ctrl_interface_number`,
 *             `iso_endpoint_address`, and `resolved_clock_source_id` must be
 *             valid (true between Phase 6 and Phase 7 of teardown_context).
 */
static void dsd_xmos_soft_reset(UsbDriverContext *ctx) noexcept
{
    const int     iface      = ctx->claimed_interface_number;
    const int     ctrl_iface = ctx->ctrl_interface_number;
    const uint8_t ep_addr    = (ctx->iso_endpoint_address != 0)
                                   ? ctx->iso_endpoint_address
                                   : static_cast<uint8_t>(0x01u); // FiiO KA5 / XMOS default
    const uint8_t resolved_csid = ctx->resolved_clock_source_id;

    TDLOGI("teardown_context [DSD soft-reset]: BEGIN — "
           "iface=%d  ep=0x%02X  ctrl_iface=%d  resolved_csid=%u",
           iface,
           static_cast<unsigned>(ep_addr),
           ctrl_iface,
           static_cast<unsigned>(resolved_csid));

    // ── Step 1: Clear ENDPOINT_HALT FIRST (before Alt 0) ─────────────────────
    //
    // The ISO OUT endpoint is typically halted/stalled after a DSD session ends
    // and all transfers are cancelled.  This HALT condition MUST be cleared
    // before the SET_INTERFACE alt=0 command is issued — if the endpoint remains
    // halted, the XMOS firmware may silently suppress the Alt-0 state change and
    // leave the DSD clock domain active.
    //
    // Step 1 must precede Step 2 (Alt-0) for this reason.  The previous order
    // (Alt-0 first, halt-clear second) caused the Alt-0 to be ignored by the
    // firmware when the endpoint was stalled, defeating the entire soft-reset.
    //
    // LIBUSB_ERROR_NOT_FOUND means the endpoint was not halted — normal path.
    {
        const int ret = libusb_clear_halt(ctx->device_handle, ep_addr);
        if (ret == LIBUSB_SUCCESS) {
            TDLOGI("teardown_context [DSD soft-reset]: Step 1 clear_halt(ep=0x%02X) ✓ — "
                   "HALT condition cleared; Alt-0 will be accepted by firmware",
                   static_cast<unsigned>(ep_addr));
        } else if (ret == LIBUSB_ERROR_NOT_FOUND) {
            TDLOGD("teardown_context [DSD soft-reset]: Step 1 ep=0x%02X not halted — "
                   "no clear needed (endpoint was already armed)",
                   static_cast<unsigned>(ep_addr));
        } else if (ret == LIBUSB_ERROR_NO_DEVICE) {
            TDLOGI("teardown_context [DSD soft-reset]: Step 1 → NO_DEVICE — "
                   "device disconnected; aborting soft-reset (non-fatal)");
            return;
        } else {
            TDLOGW("teardown_context [DSD soft-reset]: Step 1 clear_halt(ep=0x%02X) "
                   "returned %s (%d) — non-fatal; proceeding to Alt-0",
                   static_cast<unsigned>(ep_addr), libusb_error_name(ret), ret);
        }
    }

    // ── Step 2: Drop streaming interface to Alt 0 (quiesce DSD endpoint) ─────
    //
    // With the HALT cleared in Step 1, SET_INTERFACE alt=0 now reaches the XMOS
    // DSD state machine.  The firmware disables its ISO DMA engine and flushes
    // the internal USB isochronous FIFO, stopping the high-frequency DSD clock
    // domain.  This is a necessary precondition for the PLL clock SET_CUR in
    // Step 3 — the internal clock register is write-protected while the DSD ISO
    // endpoint is active.
    {
        const int ret = libusb_set_interface_alt_setting(ctx->device_handle, iface, 0);
        if (ret == LIBUSB_SUCCESS) {
            TDLOGI("teardown_context [DSD soft-reset]: Step 2 Alt-0 ✓ — "
                   "DSD ISO endpoint quiesced; PLL register now writable");
        } else if (ret == LIBUSB_ERROR_NO_DEVICE) {
            TDLOGI("teardown_context [DSD soft-reset]: Step 2 → NO_DEVICE — "
                   "device disconnected; aborting soft-reset (non-fatal)");
            return;
        } else {
            TDLOGW("teardown_context [DSD soft-reset]: Step 2 Alt-0 returned %s (%d) — "
                   "non-fatal; chip may already be idle; proceeding to clock reset",
                   libusb_error_name(ret), ret);
        }
    }

    // ── Step 3: Send 44100 Hz SET_CUR to the KNOWN-GOOD clock source ID ──────
    //
    // Root cause of the -9 LIBUSB_ERROR_PIPE failure in the previous sequence:
    //   The old code iterated through speculative candidates { 1, 41, 40 }.
    //   Candidates 41 and 40 do not exist on the FiiO KA5 production firmware
    //   (they belong to QCC5100 / early beta chips).  When the XMOS chip receives
    //   a SET_CUR targeting a non-existent entity, it STALLs the control pipe —
    //   which libusb reports as LIBUSB_ERROR_PIPE (-9).  A STALL on EP0 takes
    //   the control endpoint offline for the remainder of the session, so any
    //   subsequent SET_CUR (including IDs that WOULD have worked) also returns -9.
    //
    // Fix: use ONLY the bClockID that was positively ACK'd by the DAC during
    //   nativeSetUac2ClockSampleRate() (stored in ctx->resolved_clock_source_id).
    //   If the resolved ID is unavailable (0), fall back conservatively to ID=1
    //   (UAC2 reference default, correct for FiiO KA5 production) without trying
    //   IDs 41 or 40, which are guaranteed PIPE on this hardware.
    if (ctrl_iface >= 0) {
        // wValue = CS_SAM_FREQ_CONTROL (0x01) << 8 | master_channel (0x00)
        // UAC2 spec §5.2.5.1 / §5.2.1
        static constexpr uint16_t W_VALUE_CLOCK_FREQ = 0x0100u;

        // 44100 Hz in 4-byte little-endian (UAC2 §5.2.5.1 dCUR field).
        // Chosen because it is unambiguously "not a DSD clock rate" on every
        // known DAC and forces the XMOS PLL to disengage from its DSD oscillator.
        static constexpr uint32_t BASELINE_RATE_HZ = 44100u;
        unsigned char rate_data[4] = {
            static_cast<unsigned char>( BASELINE_RATE_HZ        & 0xFFu),
            static_cast<unsigned char>((BASELINE_RATE_HZ >>  8) & 0xFFu),
            static_cast<unsigned char>((BASELINE_RATE_HZ >> 16) & 0xFFu),
            static_cast<unsigned char>((BASELINE_RATE_HZ >> 24) & 0xFFu),
        };

        // Build a minimal, targeted candidate list:
        //   • resolved_csid (> 0): the bClockID the DAC already ACK'd — try first.
        //   • 1                  : UAC2 reference default; safe on all known DACs
        //                          and correct for FiiO KA5 production firmware.
        //   • Candidates 41/40 are deliberately excluded — they exist only on
        //     QCC5100 / early-beta firmware and trigger LIBUSB_ERROR_PIPE (-9) on
        //     the production XMOS chip, stalling the control endpoint.
        // Sentinel 0 terminates the list.
        int candidates[3] = { 0, 0, 0 };
        int n = 0;
        if (resolved_csid > 0) {
            candidates[n++] = static_cast<int>(resolved_csid);
        }
        // Always include ID=1 as a safe fallback, but skip if already in the list.
        if (n == 0 || resolved_csid != 1u) {
            candidates[n++] = 1;
        }
        // candidates[n] remains 0 — sentinel.

        bool clock_reset_ok = false;
        for (int i = 0; candidates[i] != 0 && !clock_reset_ok; ++i) {
            const auto clock_id = static_cast<uint8_t>(candidates[i]);
            // wIndex = (bClockID << 8) | Audio Control interface number
            const auto w_index  = static_cast<uint16_t>(
                    (static_cast<uint16_t>(clock_id) << 8u) |
                     static_cast<uint16_t>(ctrl_iface));

            const int ret = libusb_control_transfer(
                    ctx->device_handle,
                    0x21u,               // bmRequestType: H→D | Class | Interface
                    0x01u,               // bRequest: SET_CUR
                    W_VALUE_CLOCK_FREQ,  // wValue = 0x0100
                    w_index,             // wIndex = (clockId<<8) | ctrlIface
                    rate_data,
                    4,
                    200u);               // 200 ms timeout

            if (ret == 4) {
                TDLOGI("teardown_context [DSD soft-reset]: Step 3 clock SET_CUR ✓ — "
                       "44100 Hz baseline sent to bClockID=%u ctrl_iface=%d; "
                       "XMOS PLL disengaged from DSD oscillator",
                       static_cast<unsigned>(clock_id), ctrl_iface);
                clock_reset_ok = true;
            } else {
                const bool has_next = (candidates[i + 1] != 0);
                TDLOGW("teardown_context [DSD soft-reset]: Step 3 clock SET_CUR "
                       "bClockID=%u returned %s (%d) — %s",
                       static_cast<unsigned>(clock_id),
                       libusb_error_name(ret), ret,
                       has_next ? "trying next candidate" : "no more candidates");
            }
        }

        if (!clock_reset_ok) {
            TDLOGW("teardown_context [DSD soft-reset]: Step 3 clock reset failed "
                   "(resolved_csid=%u) — XMOS PLL may remain at DSD rate; "
                   "next PCM session may still encounter FSR ERROR. "
                   "Verify bClockID with a USB descriptor dump if this persists.",
                   static_cast<unsigned>(resolved_csid));
        }
    } else {
        TDLOGW("teardown_context [DSD soft-reset]: Step 3 skipped — "
               "ctrl_interface_number == -1 (Audio Control was never claimed); "
               "XMOS PLL may remain at DSD rate");
    }

    // ── Step 4: 100 ms PLL stabilisation window ───────────────────────────────
    //
    // After the SET_CUR ACK the XMOS PLL begins its re-lock cycle from the DSD
    // oscillator to the 44100 Hz PCM-compatible setting.  This window is extended
    // to 100 ms (up from 50 ms) for three reasons:
    //
    //   1. The DSD-to-PCM PLL transition covers a 2–4× frequency step
    //      (e.g., 88,200 Hz DSD_U32 → 44,100 Hz PCM) — a wider swing than a
    //      PCM-to-PCM change and therefore takes longer to settle.
    //   2. Some XMOS firmware variants write the new clock configuration to
    //      non-volatile internal registers after the SET_CUR ACK; the write
    //      completes within ~80 ms but a spurious libusb_release_interface()
    //      during the write can corrupt the stored configuration.
    //   3. The 100 ms window is still within the DSD stall-detection timeout
    //      used by DsdPlaybackManager (200 ms), so it does not introduce
    //      user-perceptible latency in a typical stop/play transition.
    usleep(100000u);   // 100 ms

    TDLOGI("teardown_context [DSD soft-reset]: DONE — "
           "XMOS chip returned to PCM-safe idle state; "
           "proceeding to interface release (Phase 7)");
}

// ─────────────────────────────────────────────────────────────────────────────
// teardown_context — Step 10 implementation
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Release all driver resources in a strictly ordered nine-phase sequence.
 *
 * The critical invariant is that the libusb event thread (Phase 4) MUST remain
 * running until all CANCELLED callbacks have fired (Phase 3), because:
 *
 *   libusb_cancel_transfer() is asynchronous — the host controller delivers the
 *   cancellation acknowledgement through the event loop.  Stopping the event
 *   loop first would leave TransferSlot::in_flight permanently true, and
 *   subsequently calling libusb_free_transfer() on those slots would be
 *   undefined behaviour (the callback code path dereferences the freed struct).
 *
 * Each phase is guarded by a null / validity check so a partially-constructed
 * context (e.g. init succeeded but pool allocation failed) is cleaned up
 * without any phase attempting to dereference a null pointer.
 *
 * @param ctx  Non-null pointer to the driver context.
 *             The caller is responsible for `delete ctx` after this returns.
 */
void teardown_context(UsbDriverContext *ctx) noexcept
{
    TDLOGI("teardown_context: BEGIN  fd=%d  device_handle=%p",
           ctx->device_fd,
           static_cast<void *>(ctx->device_handle));

    (void)ctx->playback_state.transition_to(UsbPlaybackState::Stopping);
    if (ctx->producer_coordinator) {
        ctx->producer_coordinator->quiesce();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Phase 1 — Mark every slot shutdown=true  (stop resubmission)
    //
    // MUST happen before Phase 2 (cancel) and while the event thread is running.
    //
    // Without this step there is a race:
    //   Thread A (here): calls libusb_cancel_transfer(slot[i])
    //   Thread B (event): processes an earlier completion, resubmits slot[i]
    //   Thread A: the cancel lands on the freshly re-submitted transfer, not
    //             the one we intended — the original transfer is now orphaned
    //             with in_flight == true but no pending cancellation.
    //
    // Setting shutdown=true first ensures the callback never resubmits,
    // regardless of the race window between our cancel request and the host
    // controller's acknowledgement.
    //
    // Memory order: release — pairs with the acquire load in iso_transfer_callback
    // shutdown guard (step 1 of the callback).  Stores to pool memory that
    // precede this release are visible to the callback when it reads shutdown=true.
    // ══════════════════════════════════════════════════════════════════════════
    if (ctx->transfer_pool) {
        auto &slots  = ctx->transfer_pool->slots();
        const auto n = slots.size();

        TDLOGD("teardown_context Phase 1: marking %zu slot(s) shutdown=true "
               "to suppress callback re-submission", n);

        for (auto &slot : slots) {
            slot.shutdown.store(true, std::memory_order_release);
        }

        TDLOGI("teardown_context Phase 1: DONE — all %zu slot(s) marked shutdown", n);
    } else {
        TDLOGD("teardown_context Phase 1: skipped (no transfer pool)");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Phase 2 — libusb_cancel_transfer() on every in-flight transfer
    //
    // libusb_cancel_transfer() places a cancellation request on libusb's
    // internal pending-transfer list.  The actual cancellation acknowledgement
    // (LIBUSB_TRANSFER_CANCELLED status) arrives through the event loop and
    // triggers iso_transfer_callback, which clears TransferSlot::in_flight.
    //
    // Cancelling all slots unconditionally (not just in-flight ones) is safe:
    //   • slot.in_flight == true  → transfer is active; cancel is issued.
    //   • slot.in_flight == false → transfer is idle; libusb returns
    //     LIBUSB_ERROR_NOT_FOUND, which is explicitly non-fatal here.
    //
    // Possible return values:
    //   LIBUSB_SUCCESS          — cancellation queued successfully.
    //   LIBUSB_ERROR_NOT_FOUND  — transfer already completed; harmless.
    //   LIBUSB_ERROR_NO_DEVICE  — DAC physically unplugged; no callback
    //                             will fire, but the event thread's NO_DEVICE
    //                             path has already set shutdown=true and
    //                             cleared in_flight in the callback.
    //   any other negative      — unexpected host-controller fault; log and
    //                             continue (the Phase 3 timeout handles it).
    //
    // ⚠️  The event thread MUST still be running here.  Cancellations are
    //     only processed when the event loop calls
    //     libusb_handle_events_timeout_completed().
    // ══════════════════════════════════════════════════════════════════════════
    if (ctx->transfer_pool) {
        const auto  &transfers = ctx->transfer_pool->transfers();
        auto        &slots     = ctx->transfer_pool->slots();
        const auto   n         = slots.size();

        uint32_t cancellations_issued  = 0;
        uint32_t already_idle          = 0;
        uint32_t cancel_errors         = 0;

        TDLOGD("teardown_context Phase 2: issuing libusb_cancel_transfer "
               "on %zu slot(s)", n);

        for (std::size_t i = 0; i < n; ++i) {
            // Only cancel transfers that are still in-flight.
            // Calling libusb_cancel_transfer on an idle transfer returns
            // LIBUSB_ERROR_NOT_FOUND, which is harmless but generates extra
            // log noise.  Checking in_flight first keeps the log clean.
            if (!slots[i].in_flight.load(std::memory_order_acquire)) {
                ++already_idle;
                continue;
            }

            const int rc = libusb_cancel_transfer(transfers[i]);

            if (rc == LIBUSB_SUCCESS) {
                ++cancellations_issued;
                TDLOGD("  slot[%zu]: cancel queued", i);

            } else if (rc == LIBUSB_ERROR_NOT_FOUND) {
                // Race: the transfer completed between our in_flight check and
                // the cancel call.  The callback already cleared in_flight; no
                // further action needed.
                ++already_idle;
                TDLOGD("  slot[%zu]: already completed at cancel time "
                       "(LIBUSB_ERROR_NOT_FOUND) — no-op", i);

            } else if (rc == LIBUSB_ERROR_NO_DEVICE) {
                // DAC was physically unplugged.  The event thread's NO_DEVICE
                // path (iso_transfer_callback Triage::Shutdown) will have set
                // shutdown=true and cleared in_flight.  The Phase 3 poll will
                // find this slot already idle.
                ++already_idle;
                TDLOGW("  slot[%zu]: LIBUSB_ERROR_NO_DEVICE at cancel time "
                       "— DAC disconnected; slot will drain in Phase 3", i);

            } else {
                // Unexpected error (e.g., LIBUSB_ERROR_IO — host controller
                // fault).  The slot may remain in_flight; Phase 3 will wait up
                // to kCancellationTimeout, then proceed with forced teardown.
                ++cancel_errors;
                TDLOGW("  slot[%zu]: unexpected cancel error: %s (%d)",
                       i, libusb_error_name(rc), rc);
            }
        }

        TDLOGI("teardown_context Phase 2: DONE — "
               "cancellations_issued=%u  already_idle=%u  errors=%u  total=%zu",
               cancellations_issued, already_idle, cancel_errors, n);
    } else {
        TDLOGD("teardown_context Phase 2: skipped (no transfer pool)");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Phase 3 — Wait for all CANCELLED callbacks to clear in_flight flags
    //
    // The event thread drives this process: its libusb_handle_events_*() call
    // picks up the cancellation event from the host controller and invokes
    // iso_transfer_callback, which sets in_flight = false for each slot.
    //
    // ⚠️  WE MUST NOT stop the event thread before this phase completes.
    //
    // The loop:
    //   1. Count remaining in_flight == true slots.
    //   2. If zero → all callbacks received, proceed to Phase 4.
    //   3. If deadline exceeded → log ERROR, proceed anyway (forced teardown).
    //   4. Otherwise sleep kCancellationPollInterval (500 µs) and retry.
    //
    // The sleep relinquishes the CPU to the event thread, which needs a
    // scheduler slot to call libusb_handle_events().  Without the sleep this
    // loop would spin-lock at 100% CPU and may starve the event thread on a
    // congested SoC scheduler, paradoxically extending the wait.
    //
    // The acquire load on in_flight pairs with the release store in the
    // callback's shutdown-guard and DrainOnly paths.  This guarantees we do
    // not observe a stale in_flight=true after the callback has written false.
    // ══════════════════════════════════════════════════════════════════════════
    if (ctx->transfer_pool &&
        ctx->event_thread  &&
        ctx->event_thread->is_running())
    {
        TDLOGI("teardown_context Phase 3: waiting for CANCELLED callbacks "
               "(timeout=%lld ms, poll_interval=500 µs)...",
               static_cast<long long>(kCancellationTimeout.count()));

        const auto deadline = std::chrono::steady_clock::now() + kCancellationTimeout;
        uint32_t   remaining = 0;

        do {
            remaining = 0;

            for (const auto &slot : ctx->transfer_pool->slots()) {
                if (slot.in_flight.load(std::memory_order_acquire)) {
                    ++remaining;
                }
            }

            if (remaining == 0) {
                break;   // All callbacks received — fast exit path.
            }

            if (std::chrono::steady_clock::now() >= deadline) {
                // ── Timeout: forced teardown ──────────────────────────────────
                // Hardware may be unresponsive (wedged SoC host controller, or
                // cable removed in the tiny window between Phase 1 and Phase 2).
                // Log at ERROR so the developer can diagnose the condition.
                // Proceeding with forced teardown is the lesser evil vs. hanging
                // the app indefinitely — on a correctly-implemented SoC the
                // callback will have already fired and this path never triggers.
                TDLOGE("teardown_context Phase 3: TIMEOUT — %u slot(s) still"
                       " in_flight after %lld ms.  "
                       "DAC may be physically disconnected or SoC host "
                       "controller wedged.  Forcing teardown (WARNING: if a "
                       "stale callback fires after pool destruction on a wedged "
                       "SoC it will dereference freed pool memory — this is an "
                       "OS/hardware-level race that cannot be avoided without a "
                       "kernel driver)",
                       remaining,
                       static_cast<long long>(kCancellationTimeout.count()));
                break;
            }

            // Yield: let the event thread run and process the CANCELLED events.
            std::this_thread::sleep_for(kCancellationPollInterval);

        } while (true);

        if (remaining == 0) {
            TDLOGI("teardown_context Phase 3: DONE — "
                   "all CANCELLED callbacks received; in_flight cleared on all slots");
        }

    } else {
        // Pool not yet allocated, event thread not started, or already stopped
        // (e.g., the event thread self-terminated due to consecutive errors).
        TDLOGD("teardown_context Phase 3: skipped "
               "(no pool, or event_thread null/stopped)");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Phase 4 — Stop and join the libusb event thread
    //
    // After join() COMPLETES:
    //   • thread_fn() has returned — no callback can ever fire again.
    //   • All in_flight flags are stable (settled in Phase 3 or the thread's own
    //     shutdown path).
    //   • The IsoTransferPool (Phase 6) can be safely destroyed.
    //
    // LibusbEventThread::stop() does in order:
    //   1. keep_running_.store(false, release) — signals the loop to exit.
    //   2. libusb_interrupt_event_handler(ctx_) — wakes a blocked poll in < 1 ms,
    //      reducing teardown latency from up to 100 ms (kPollTimeoutUs ceiling)
    //      to < 1 ms in practice.  (Guard: available since libusb 1.0.21.)
    //   3. thread_.join()  — full memory fence; waits for thread_fn to return.
    //
    // Resetting the unique_ptr calls the destructor, which calls stop() —
    // so `reset()` below is equivalent to `event_thread->stop(); event_thread = nullptr`.
    // ══════════════════════════════════════════════════════════════════════════
    if (ctx->event_thread) {
        TDLOGI("teardown_context Phase 4: stopping libusb event thread "
               "(underrun_count=%llu  error_count=%u)",
               ctx->transfer_pool
                   ? static_cast<unsigned long long>(
                         ctx->transfer_pool->underrun_count())
                   : 0ULL,
               ctx->event_thread->error_count());

        ctx->event_thread.reset();   // → ~LibusbEventThread() → stop() → join()

        TDLOGI("teardown_context Phase 4: DONE — event thread joined and destroyed");
    } else {
        TDLOGD("teardown_context Phase 4: skipped (event thread was never started)");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Phase 5 — Destroy the SPSC ring buffer
    //
    // Safe because:
    //   • The consumer (iso_transfer_callback) has stopped — event thread joined
    //     in Phase 4, so no concurrent reader exists.
    //   • Phase 0 quiesced and joined the sole producer through
    //     UsbProducerCoordinator, so no concurrent writer exists.
    //
    // Capacity + size are logged for post-mortem underrun diagnostics.
    // ══════════════════════════════════════════════════════════════════════════
    if (ctx->ring_buffer) {
        TDLOGD("teardown_context Phase 5: destroying ring buffer "
               "(capacity=%zu B  unread=%zu B  free=%zu B)",
               ctx->ring_buffer->capacity(),
               ctx->ring_buffer->size(),        // bytes pending / not yet consumed
               ctx->ring_buffer->free_space()); // bytes available for producer

        ctx->ring_buffer.reset();   // → SpscRingBuffer destructor → free()

        TDLOGD("teardown_context Phase 5: DONE — ring buffer freed");
    } else {
        TDLOGD("teardown_context Phase 5: skipped (ring buffer was never created)");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Phase 6 — Destroy the isochronous transfer pool
    //
    // IsoTransferPool::~IsoTransferPool() calls release_all(), which:
    //   a) Calls libusb_free_transfer()   — each libusb_transfer struct.
    //      (Nullifies transfer->buffer pointer first as a guard measure.)
    //   b) Calls free()                  — each posix_memalign'd payload buffer.
    //   c) Calls slots_.clear()          — inline TransferSlot vector.
    //
    // Pre-conditions satisfied at this point:
    //   ✅  Phase 3 waited for all in_flight flags to become false.
    //   ✅  Phase 4 joined the event thread → no callback can ever fire.
    //   → libusb_free_transfer() is safe for every slot.
    //
    // LIBUSB_TRANSFER_FREE_BUFFER and LIBUSB_TRANSFER_FREE_TRANSFER flags are
    // NOT set on the pool transfers (see IsoTransferPool::allocate step 7).
    // The pool destructor manages all memory explicitly to prevent double-free.
    // ══════════════════════════════════════════════════════════════════════════
    if (ctx->transfer_pool) {
        const uint32_t pool_size    = ctx->transfer_pool->config().pool_size;
        const uint64_t underruns    = ctx->transfer_pool->underrun_count();

        TDLOGD("teardown_context Phase 6: destroying transfer pool "
               "(%u slot(s), lifetime underruns=%llu)",
               pool_size,
               static_cast<unsigned long long>(underruns));

        ctx->transfer_pool.reset();  // → ~IsoTransferPool() → release_all()

        TDLOGI("teardown_context Phase 6: DONE — "
               "%u transfer struct(s) and payload buffer(s) freed", pool_size);
    } else {
        TDLOGD("teardown_context Phase 6: skipped (pool was never allocated)");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Phase 6.5 — XMOS/FiiO DSD Soft Reset (DSD sessions only)
    //
    // Must execute AFTER Phase 6 (pool + ring destroyed  — no concurrent ISO
    // callbacks can interfere) and BEFORE Phase 7 (interface still claimed,
    // device handle still open, ctrl_interface also still valid).
    //
    // Skipped for PCM-only sessions (ctx->was_dsd_session == false).
    //
    // See the dsd_xmos_soft_reset() function above for the full rationale,
    // sequence, and per-step commentary.
    // ══════════════════════════════════════════════════════════════════════════
    if (ctx->was_dsd_session &&
        ctx->device_handle  != nullptr &&
        ctx->claimed_interface_number >= 0)
    {
        TDLOGI("teardown_context Phase 6.5: was_dsd_session=true — "
               "executing XMOS soft-reset to release DSD DSP lock "
               "before interface release");
        dsd_xmos_soft_reset(ctx);
    } else {
        TDLOGD("teardown_context Phase 6.5: skipped "
               "(was_dsd_session=%s  handle=%p  iface=%d)",
               ctx->was_dsd_session ? "true" : "false",
               static_cast<void *>(ctx->device_handle),
               ctx->claimed_interface_number);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Phases 7–9 — USB interface release, device close, context exit
    //
    // These three operations must happen in this exact order.  Reversing any
    // pair is fatal:
    //
    //   • libusb_close() before uac2_release_interface():
    //     libusb_close() does NOT issue USBDEVFS_RELEASEINTERFACE.  If we close
    //     first, the kernel retains a stale exclusive claim on the interface
    //     for the lifetime of the (now-closed) FD reference, which in practice
    //     means the DAC cannot be reclaimed by AudioFlinger or a reconnection
    //     attempt until the device is physically re-plugged.
    //
    //   • libusb_exit() before libusb_close():
    //     libusb_exit() destroys the context's internal transfer tracking
    //     structures.  Any outstanding handle or transfer associated with the
    //     context after exit is a dangling reference.  Always close the handle
    //     first, then exit the context.
    // ══════════════════════════════════════════════════════════════════════════

    if (ctx->device_handle != nullptr) {

        // ── Phase 7: Release the USB interface claim ──────────────────────────
        // uac2_release_interface() does two things:
        //   1. SET_INTERFACE alt=0 (courtesy: tells DAC to quiesce endpoint and
        //      enter low-power idle — failure is non-fatal and logged at WARN).
        //   2. libusb_release_interface() → USBDEVFS_RELEASEINTERFACE ioctl:
        //      releases kernel-side exclusive claim; snd-usb-audio or the next
        //      connected UsbDeviceConnection can reclaim the interface.
        //      If auto-detach was armed (Phase 1 of init), libusb will also
        //      issue USBDEVFS_CONNECT here to re-attach the kernel driver.
        //
        // Passing claimed_interface_number == -1 is a safe no-op (handled by
        // uac2_release_interface's null guard) — covers the case where
        // nativeClaimInterface was never called or failed.
        if (ctx->claimed_interface_number >= 0) {
            TDLOGD("teardown_context Phase 7: releasing streaming interface claim "
                   "(iface=%d  was_alt=%d)",
                   ctx->claimed_interface_number,
                   ctx->active_alt_setting);

            uac2_release_interface(ctx->device_handle,
                                   ctx->claimed_interface_number);

            ctx->claimed_interface_number = -1;
            ctx->active_alt_setting       = -1;

            TDLOGI("teardown_context Phase 7: streaming interface released");
        } else {
            TDLOGD("teardown_context Phase 7: skipped streaming release "
                   "(claimed_interface_number == -1)");
        }

        // ── Phase 7b: Release the Audio Control interface if claimed ──────────
        // nativeSetUac2ClockSampleRate() claims interface 0 (Audio Control)
        // before the UAC2 SET_CUR clock transfer; it must be released here so
        // the kernel-side claim does not persist after the session ends.
        //
        // Unlike the streaming interface, the Control interface has no alternate
        // settings to deactivate — a straight libusb_release_interface() is
        // sufficient.  The SET_INTERFACE alt=0 courtesy step is intentionally
        // skipped here because interface 0 carries no isochronous endpoints.
        if (ctx->ctrl_interface_number >= 0) {
            TDLOGD("teardown_context Phase 7b: releasing audio control interface "
                   "(iface=%d)", ctx->ctrl_interface_number);

            const int rel_ret = libusb_release_interface(
                    ctx->device_handle, ctx->ctrl_interface_number);

            if (rel_ret == LIBUSB_SUCCESS) {
                TDLOGI("teardown_context Phase 7b: ctrl interface %d released OK",
                       ctx->ctrl_interface_number);
            } else if (rel_ret == LIBUSB_ERROR_NO_DEVICE) {
                TDLOGI("teardown_context Phase 7b: LIBUSB_ERROR_NO_DEVICE — "
                       "device already disconnected; ctrl claim cleaned up by kernel");
            } else {
                TDLOGW("teardown_context Phase 7b: libusb_release_interface(ctrl=%d) "
                       "failed — %s (%d) (stale claim may persist until device reconnect)",
                       ctx->ctrl_interface_number, libusb_error_name(rel_ret), rel_ret);
            }

            ctx->ctrl_interface_number = -1;
        } else {
            TDLOGD("teardown_context Phase 7b: skipped ctrl release "
                   "(ctrl_interface_number == -1, never claimed)");
        }

        // ── Phase 8: Close the device handle ─────────────────────────────────
        // libusb_close() decrements libusb's internal reference count on the
        // underlying file descriptor.  The raw FD is NOT closed here; it
        // remains owned by the Java UsbDeviceConnection and must be closed by
        // the Java layer after nativeRelease() returns.
        //
        // Null out device_handle immediately after close so that a hypothetical
        // second teardown call (from the destructor fallback path) cannot issue
        // a double-close, which is undefined behaviour in libusb.
        TDLOGD("teardown_context Phase 8: libusb_close(handle=%p  fd=%d)",
               static_cast<void *>(ctx->device_handle),
               ctx->device_fd);

        libusb_close(ctx->device_handle);
        ctx->device_handle = nullptr;

        TDLOGI("teardown_context Phase 8: DONE — device handle closed "
               "(raw FD %d remains owned by Java UsbDeviceConnection)",
               ctx->device_fd);
    } else {
        TDLOGD("teardown_context Phase 8: skipped (device_handle is null — "
               "libusb_wrap_sys_device never succeeded)");
    }

    // ── Phase 9: Destroy the libusb session context ───────────────────────────
    // libusb_exit() destroys the context and all of its internal structures
    // (transfer list, event-handling internals, poll FD set).
    //
    // Must be the very last libusb call:
    //   • All device handles opened under this context must be closed first
    //     (done in Phase 8).
    //   • libusb's internal event-handling threads (if any — the Android backend
    //     uses the calling thread for event processing, not background threads)
    //     are quiesced and torn down here.
    //
    // After this call any pointer derived from usb_ctx is invalid.
    if (ctx->usb_ctx != nullptr) {
        TDLOGD("teardown_context Phase 9: libusb_exit(ctx=%p)",
               static_cast<void *>(ctx->usb_ctx));

        libusb_exit(ctx->usb_ctx);
        ctx->usb_ctx = nullptr;

        TDLOGI("teardown_context Phase 9: DONE — libusb context destroyed");
    } else {
        TDLOGD("teardown_context Phase 9: skipped (usb_ctx is null — "
               "libusb_init never succeeded)");
    }

    // ── Final cleanup ─────────────────────────────────────────────────────────
    // Sentinel the FD field so any accidental post-teardown log reads show -1
    // rather than a stale fd value that could be misinterpreted.
    ctx->device_fd = -1;
    (void)ctx->playback_state.transition_to(UsbPlaybackState::Stopped);

    TDLOGI("teardown_context: COMPLETE — "
           "all libusb resources released, all threads joined, "
           "all pool memory freed");
}
