// ─────────────────────────────────────────────────────────────────────────────
// usb_teardown.h
//
// Step 10 — Safe teardown sequence for the user-space USB Audio Class 2.0 driver.
//
// This module owns the single entry point `teardown_context()`, which releases
// every resource allocated across Steps 1–9 in the exact order required to avoid
// use-after-free crashes, kernel USB state corruption, and memory leaks.
//
// ── Why the ordering is non-negotiable ────────────────────────────────────────
//
// The naive "stop event thread first, then cancel transfers" order is FATAL:
//
//   • libusb_cancel_transfer() is asynchronous.  The cancellation acknowledgement
//     from the USB host controller is delivered through the event loop as a
//     LIBUSB_TRANSFER_CANCELLED callback.
//   • If the event thread is stopped before the CANCELLED callbacks have fired,
//     TransferSlot::in_flight remains `true` forever.
//   • LibusbTransferPool::release_all() / libusb_free_transfer() on a still-
//     in-flight transfer is undefined behaviour: the (now-dead) event thread's
//     callback code path would dereference the freed struct.
//
// The correct order:
//
//                ┌────────────────────────────────────────────────────┐
//                │  Event thread still running during phases 1–3      │
//                │  (it must process the CANCELLED callbacks)         │
//                ├─────┬──────────────────────────────────────────────┤
//                │  1  │ Set every TransferSlot::shutdown = true       │
//                │     │ (prevents callback from re-submitting)        │
//                ├─────┼──────────────────────────────────────────────┤
//                │  2  │ libusb_cancel_transfer() on each in-flight    │
//                │     │ transfer; LIBUSB_ERROR_NOT_FOUND is harmless  │
//                ├─────┼──────────────────────────────────────────────┤
//                │  3  │ Spin-poll in_flight until all are false, with │
//                │     │ a 2-second bounded wait for hardware hangs    │
//                ├─────┴──────────────────────────────────────────────┤
//                │  4  │ stop() + join the event thread                │
//                ├─────┼──────────────────────────────────────────────┤
//                │  5  │ Destroy SpscRingBuffer                        │
//                ├─────┼──────────────────────────────────────────────┤
//                │  6  │ Destroy IsoTransferPool                       │
//                │     │ (libusb_free_transfer + free per slot)        │
//                ├─────┼──────────────────────────────────────────────┤
//                │ 6.5 │ XMOS DSD Soft Reset (DSD sessions only)       │
//                │     │ ① SET_INTERFACE alt=0  — quiesce DSD endpoint │
//                │     │ ② libusb_clear_halt    — clear endpoint stall │
//                │     │ ③ UAC2 SET_CUR 44100Hz — restore PLL baseline │
//                │     │ ④ usleep(50 ms)        — PLL re-lock window   │
//                │     │ Releases XMOS DSD DSP lock so the next PCM   │
//                │     │ session can set its clock without FSR ERROR.  │
//                ├─────┼──────────────────────────────────────────────┤
//                │  7  │ uac2_release_interface                        │
//                │     │ (SET_INTERFACE alt=0, USBDEVFS_RELEASEINTERF) │
//                ├─────┼──────────────────────────────────────────────┤
//                │  8  │ libusb_close(device_handle)                   │
//                ├─────┼──────────────────────────────────────────────┤
//                │  9  │ libusb_exit(usb_ctx)                          │
//                └─────┴──────────────────────────────────────────────┘
//
// ── Partial-initialisation safety ────────────────────────────────────────────
//
// Each phase is guarded by a null/valid check so `teardown_context()` can be
// called safely at any point during a failed init sequence — for example, if
// nativeAllocateTransferPool succeeded but nativeStartPlayback failed.  Fields
// that were never populated (nullptr / -1) are silently skipped.
//
// ── Android hardware disconnect handling ──────────────────────────────────────
//
// When the user physically unplugs the DAC mid-stream:
//   • The next libusb event-loop iteration returns LIBUSB_ERROR_NO_DEVICE.
//   • The iso_transfer_callback receives LIBUSB_TRANSFER_NO_DEVICE and sets
//     TransferSlot::shutdown = true autonomously.
//   • libusb_cancel_transfer() during Phase 2 of teardown returns
//     LIBUSB_ERROR_NOT_FOUND (transfer already completed) — harmless.
//   • The Phase 3 poll finds all in_flight flags already false quickly.
//
// ─────────────────────────────────────────────────────────────────────────────

#pragma once

// Forward declaration — full type defined in usb_driver_context.h.
// Callers that need to allocate or inspect the context must include
// usb_driver_context.h directly; this header is intentionally minimal.
struct UsbDriverContext;

// ─────────────────────────────────────────────────────────────────────────────
// Public API
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Release all driver resources in the exact nine-phase order required to avoid
 * use-after-free crashes and kernel USB state corruption.
 *
 * ### Pre-conditions
 *
 * The Kotlin layer is responsible for:
 *   1. Stopping the FFmpeg decoder / audio producer thread before calling
 *      `nativeRelease()` so the SPSC ring buffer has no concurrent writer
 *      when Phase 5 destroys it.
 *   2. Not touching any JNI native method after `nativeRelease()` returns —
 *      the context pointer is `delete`d by the JNI wrapper immediately after
 *      this function returns.
 *
 * ### Post-conditions
 *
 * On return:
 *   - All libusb resources (context, device handle) are freed.
 *   - All POSIX threads owned by the driver have been joined.
 *   - All heap allocations owned by the driver have been freed.
 *   - All fields on `ctx` that were valid are now null / -1.
 *   - The USB interface claim has been released (kernel state restored).
 *
 * ### Fault tolerance
 *
 * The function is `noexcept` and handles every error path internally:
 *   - A physically-disconnected DAC (LIBUSB_ERROR_NO_DEVICE at any phase)
 *     is logged and silently skipped; teardown continues.
 *   - A hung cancellation that exceeds `kCancellationTimeout` (2 s) triggers
 *     an ERROR log and forced continuation to prevent an infinite hang.
 *   - A `libusb_release_interface()` failure is logged as ERROR; it indicates
 *     a stale kernel claim that will self-resolve at next device reconnect.
 *
 * @param ctx  Non-null pointer to the driver context to tear down.
 *             The caller must `delete ctx` after this function returns.
 *
 * @see UsbDriverContext
 * @see IsoTransferPool
 * @see LibusbEventThread
 * @see uac2_release_interface
 */
void teardown_context(UsbDriverContext *ctx) noexcept;

