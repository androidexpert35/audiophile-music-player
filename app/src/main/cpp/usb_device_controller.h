// ─────────────────────────────────────────────────────────────────────────────
// usb_device_controller.h
//
// Device-control layer for the user-space USB Audio driver.
//
// Provides the Step 3 operations that must execute exactly once between
// Step 1 (FD wrap / context init) and Step 4 (isochronous transfer setup):
//
//   1. libusb_set_auto_detach_kernel_driver — try to enlist the kernel to
//      auto-release the interface when we claim it.  On Android this call
//      is almost always non-fatal NOT_SUPPORTED — documented below.
//
//   2. libusb_claim_interface — acquire exclusive ownership of the
//      AudioStreaming interface identified by the Step 2 scanner.
//
//   3. libusb_set_interface_alt_setting — activate the non-zero alternate
//      setting so the isochronous OUT endpoint becomes active and ready to
//      accept transfers.
//
// The module also provides the symmetric release function used during teardown.
//
// ── Android kernel-driver conflict: why LIBUSB_ERROR_BUSY is fatal here ───────
//
// On Linux desktop, when a kernel driver (e.g. snd-usb-audio) owns a USB
// interface, `libusb_set_auto_detach_kernel_driver(handle, 1)` tells libusb
// to issue USBDEVFS_DISCONNECT to the kernel before the claim, and
// USBDEVFS_CONNECT to re-attach it after release.  This works transparently.
//
// On Android the picture is completely different:
//
//   a) USBDEVFS_DISCONNECT / USBDEVFS_CONNECT are not permitted for
//      app-level processes.  The SELinux policy and the Android USB permission
//      model explicitly prevent them.  As a result,
//      `libusb_set_auto_detach_kernel_driver` returns LIBUSB_ERROR_NOT_SUPPORTED
//      on virtually every Android device — this is expected and non-fatal.
//
//   b) If the OEM kernel UAC2 driver (linux/usb/gadget/ or vendor modules like
//      snd-usb-audio, qc-usb-audio) has already bound to the AudioStreaming
//      interface, `libusb_claim_interface` returns LIBUSB_ERROR_BUSY.  There is
//      NO recoverable path from this state in user-space on Android — the kernel
//      driver owns the interface and the app cannot forcibly remove it without
//      root.
//
//      Practical conditions under which LIBUSB_ERROR_BUSY does NOT occur:
//        • The device was plugged in AFTER the app already held the UsbManager
//          permission, so it got the FD before the kernel driver bound.
//        • The OEM kernel has `snd-usb-audio` compiled as a module that has not
//          been probed yet (unusual — most OEMs compile it in).
//        • The DAC's bInterfaceProtocol == 0x20 (UAC2) and the kernel driver
//          explicitly skips UAC2 interfaces (rare / vendor-specific behaviour).
//
//   c) LIBUSB_ERROR_ACCESS at claim time means the file descriptor itself lacks
//      sufficient permission (the Java layer should have caught this earlier).
//
// When LIBUSB_ERROR_BUSY is returned by uac2_claim_and_activate_interface(),
// the Kotlin layer MUST fall back to the AudioFlinger / AudioTrack path.
//
// ── wMaxPacketSize=0 alt setting 0 must never be activated ────────────────────
//
// UAC2 requires every AudioStreaming interface to have alt setting 0 with no
// endpoints (zero-bandwidth).  Calling libusb_set_interface_alt_setting to
// alt 0 is legal only to momentarily suspend the stream; doing it before the
// first activate or if the claim fails leaves the endpoint in a non-functional
// state.  The Step 2 scanner already excludes alt setting 0 from its result
// set, so this module will never receive it as input.
// ─────────────────────────────────────────────────────────────────────────────

#pragma once

#include <cstdint>

#include "uac2_descriptor_parser.h"

// Forward declaration — must not escape outside the Data layer.
struct libusb_device_handle;

// ─────────────────────────────────────────────────────────────────────────────
// Result types
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Granular status codes returned by uac2_claim_and_activate_interface().
 *
 * Each value maps to a distinct failure phase so the caller can log or
 * surface precise diagnostics without inspecting raw libusb error codes.
 */
enum class UsbControlStatus : int32_t {

    /** All three sub-operations succeeded. */
    Success = 0,

    // ── Phase 1: auto-detach flag ─────────────────────────────────────────────

    /**
     * libusb_set_auto_detach_kernel_driver() returned LIBUSB_ERROR_NOT_SUPPORTED.
     *
     * NON-FATAL on Android.  The call is treated as a best-effort hint;
     * execution continues to the claim phase.  Logged at INFO level.
     *
     * This is the expected result on every stock Android device because
     * USBDEVFS_DISCONNECT is blocked for app-level processes by SELinux.
     */
    AutoDetachNotSupported = 1,

    /**
     * libusb_set_auto_detach_kernel_driver() returned an unexpected error
     * (not LIBUSB_ERROR_NOT_SUPPORTED, not LIBUSB_SUCCESS).
     *
     * NON-FATAL — execution still proceeds to the claim phase, but the
     * underlying libusb error code is recorded for diagnostics.
     */
    AutoDetachUnexpectedError = 2,

    // ── Phase 2: interface claim ──────────────────────────────────────────────

    /**
     * libusb_claim_interface() returned LIBUSB_ERROR_BUSY.
     *
     * FATAL — the kernel UAC2 driver (snd-usb-audio or a vendor equivalent)
     * owns the interface; user-space cannot reclaim it without root privileges.
     * The Kotlin layer must fall back to the AudioFlinger / AudioTrack path.
     *
     * See the module header comment for a full explanation of when this occurs.
     */
    ClaimBusyKernelDriverOwner = 3,

    /**
     * libusb_claim_interface() returned LIBUSB_ERROR_NO_DEVICE.
     * FATAL — the DAC was physically disconnected during the init sequence.
     */
    ClaimDeviceDisconnected = 4,

    /**
     * libusb_claim_interface() returned LIBUSB_ERROR_NOT_FOUND.
     * FATAL — the interface number from Step 2 does not exist on the device.
     * Indicates a descriptor-parsing inconsistency; should never occur in practice.
     */
    ClaimInterfaceNotFound = 5,

    /**
     * libusb_claim_interface() returned LIBUSB_ERROR_ACCESS.
     * FATAL — the process lacks permission; the FD may have expired.
     */
    ClaimAccessDenied = 6,

    /**
     * libusb_claim_interface() returned any other negative libusb error code.
     * FATAL — operation failed for an unexpected reason.
     */
    ClaimUnexpectedError = 7,

    // ── Phase 3: alternate setting activation ─────────────────────────────────

    /**
     * libusb_set_interface_alt_setting() returned LIBUSB_ERROR_TIMEOUT.
     *
     * FATAL — the SET_INTERFACE control transfer to the device timed out.
     * The interface has been claimed but the endpoint is NOT active.
     * The claimed interface will be released automatically by
     * uac2_claim_and_activate_interface() before returning.
     */
    AltSettingTimeout = 8,

    /**
     * libusb_set_interface_alt_setting() returned LIBUSB_ERROR_PIPE (stall).
     *
     * FATAL — the device rejected the SET_INTERFACE control transfer.
     * This can happen on some DACs if the alternate setting index is out
     * of range or the device's firmware is in an inconsistent state.
     * The claimed interface is released automatically.
     */
    AltSettingStalled = 9,

    /**
     * libusb_set_interface_alt_setting() returned LIBUSB_ERROR_NO_DEVICE.
     * FATAL — device disconnected during SET_INTERFACE control transfer.
     * The claimed interface is released automatically.
     */
    AltSettingDeviceDisconnected = 10,

    /**
     * libusb_set_interface_alt_setting() returned any other error.
     * FATAL — unexpected error activating the isochronous endpoint.
     * The claimed interface is released automatically.
     */
    AltSettingUnexpectedError = 11,
};

/**
 * Result of uac2_claim_and_activate_interface().
 *
 * On success (status == UsbControlStatus::Success):
 *   • claimed_interface_number holds the interface that must be released
 *     via uac2_release_interface() when the driver is torn down.
 *   • active_alt_setting holds the alternate setting that was activated.
 *
 * On failure (any other status):
 *   • The function has already performed rollback — no interface remains
 *     claimed; the caller does NOT need to call uac2_release_interface().
 *   • libusb_error_code holds the raw negative libusb error for diagnostics.
 *   • claimed_interface_number and active_alt_setting are -1.
 *
 * @property success                   true when all three phases completed.
 * @property status                    Granular phase/reason enumeration.
 * @property libusb_error_code         Raw libusb return code from the failing call (≤ 0).
 * @property claimed_interface_number  bInterfaceNumber now owned by this process.
 * @property active_alt_setting        bAlternateSetting now active on the endpoint.
 * @property auto_detach_supported     false when the platform silently skipped phase 1.
 */
struct UsbInterfaceClaimResult {
    bool            success                  = false;
    UsbControlStatus status                  = UsbControlStatus::ClaimUnexpectedError;
    int             libusb_error_code        = 0;
    int             claimed_interface_number = -1;
    int             active_alt_setting       = -1;
    bool            auto_detach_supported    = false;
};

// ─────────────────────────────────────────────────────────────────────────────
// Public API
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Claim the UAC2 AudioStreaming interface and activate its isochronous alt setting.
 *
 * Executes the mandatory three-phase device-control sequence described in the
 * module header.  The three phases must complete in order; the function stops
 * and performs rollback on the first fatal error.
 *
 * ### Phase 1 — auto-detach flag (best-effort, non-fatal)
 *
 * Calls libusb_set_auto_detach_kernel_driver(handle, 1).
 * On Android this almost always returns LIBUSB_ERROR_NOT_SUPPORTED because
 * USBDEVFS_DISCONNECT is blocked by SELinux.  The result is logged and stored
 * in UsbInterfaceClaimResult::auto_detach_supported but does **not** abort
 * the sequence — the claim attempt proceeds regardless.
 *
 * ### Phase 2 — interface claim (fatal on error)
 *
 * Calls libusb_claim_interface(handle, endpoint.interface_number).
 * LIBUSB_SUCCESS gives us exclusive ownership.  Any error aborts immediately:
 *   • LIBUSB_ERROR_BUSY  → kernel UAC2 driver owns the interface
 *                          (UsbControlStatus::ClaimBusyKernelDriverOwner).
 *                          The Kotlin layer MUST fall back to AudioFlinger.
 *   • All other errors   → mapped to specific UsbControlStatus values.
 * No rollback is performed on Phase 2 failure (nothing was claimed yet).
 *
 * ### Phase 3 — alternate setting activation (fatal, with rollback)
 *
 * Calls libusb_set_interface_alt_setting(handle, interface_number, alt_setting).
 * On any error the already-claimed interface is automatically released via
 * libusb_release_interface() before this function returns, so the caller is
 * always left in a clean state without a dangling claim.
 *
 * @param handle    Open libusb device handle from Step 1.  Must not be null.
 * @param endpoint  Best UAC2 streaming endpoint from Step 2.  Must have
 *                  alt_setting ≥ 1 (Step 2 guarantees this).
 * @return          UsbInterfaceClaimResult describing success or the failure phase.
 */
UsbInterfaceClaimResult uac2_claim_and_activate_interface(
        libusb_device_handle             *handle,
        const Uac2StreamingEndpointInfo  &endpoint);

/**
 * Claim the UAC2 AudioStreaming interface WITHOUT activating any alt setting.
 *
 * Executes Phase 1 (auto-detach) and Phase 2 (claim) of the three-phase
 * sequence, deliberately skipping Phase 3 (alt-setting activation).
 *
 * This split is required for the correct UAC2 clock-setup ordering on
 * devices like the FiiO KA series:
 *
 *   Phase 1+2 — uac2_claim_interface_only()    ← claim streaming iface
 *   Clock step — nativeSetUac2ClockSampleRate() ← SET_CUR control transfer
 *   Phase 3   — uac2_apply_alt_setting()        ← activate ISO endpoint
 *
 * @param handle    Open libusb device handle.  Must not be null.
 * @param endpoint  Best UAC2 streaming endpoint from Step 2 scanner.
 * @return          UsbInterfaceClaimResult.  On success, `active_alt_setting`
 *                  is -1 (Phase 3 was not run); call uac2_apply_alt_setting()
 *                  to activate the alt setting after clock setup.
 */
UsbInterfaceClaimResult uac2_claim_interface_only(
        libusb_device_handle             *handle,
        const Uac2StreamingEndpointInfo  &endpoint);

/**
 * Activate an alternate setting on a previously-claimed interface (Phase 3).
 *
 * Issues `libusb_set_interface_alt_setting()`.  Must be called AFTER a
 * successful `uac2_claim_interface_only()` and AFTER the UAC2 clock-source
 * SET_CUR control transfer has been sent.
 *
 * When `alt_num` is non-zero (i.e., a live isochronous alt setting is being
 * activated) and `endpoint_address` is non-zero, this function immediately
 * follows the SET_INTERFACE with a `libusb_clear_halt()` on the ISO OUT
 * endpoint.  This clears any hardware STALL/HALT condition that the XMOS
 * USB receiver chip (e.g., FiiO KA5) may latch silently during the
 * Alt 0 → Alt N transition, which would otherwise cause the isochronous
 * callbacks to never fire — manifesting as a ring buffer deadlock
 * (`ring_free=0 KB`, `full_sleeps` incrementing) despite successful transfer
 * submission.  A 10 ms `usleep` after the clear gives the XMOS DSP time to
 * flush its internal buffers before the transfer pool is allocated.
 *
 * When `alt_num == 0` (Role-A cold reset / idle) or `endpoint_address == 0`,
 * `libusb_clear_halt` is skipped — it is harmless but unnecessary for the
 * zero-bandwidth interface.
 *
 * ### Failure rollback
 *
 * Unlike `uac2_claim_and_activate_interface()`, this function does NOT
 * automatically release the interface on failure.  The caller is responsible
 * for calling `uac2_release_interface()` if this returns a non-Success status.
 *
 * @param handle            Open libusb device handle.
 * @param iface_num         bInterfaceNumber of the claimed streaming interface.
 * @param alt_num           Desired alternate setting (0 = idle; ≥ 1 = ISO active).
 * @param endpoint_address  `bEndpointAddress` of the isochronous OUT endpoint
 *                          (e.g., `0x01`).  Pass `0` to skip `libusb_clear_halt`.
 * @return                  `UsbControlStatus::Success` on success; one of the
 *                          `AltSetting*` codes on failure.
 */
UsbControlStatus uac2_apply_alt_setting(
        libusb_device_handle *handle,
        int                   iface_num,
        int                   alt_num,
        int                   endpoint_address = 0);

/**
 * Release a previously claimed interface and restore the zero-bandwidth alt setting.
 *
 * Must be called during driver teardown BEFORE libusb_close() and AFTER all
 * in-flight isochronous transfers have been cancelled and freed.
 *
 * Calling libusb_set_interface_alt_setting() back to alt setting 0 is a
 * best-effort courtesy to the device — it tells the DAC that the host is no
 * longer streaming so the DAC can enter low-power idle.  Failure is logged
 * but does not prevent the interface release from proceeding.
 *
 * Passing interface_number == -1 is a safe no-op (covers the case where the
 * claim never succeeded and the caller still calls release unconditionally).
 *
 * @param handle              Open device handle.  Must not be null.
 * @param interface_number    bInterfaceNumber to release (-1 = no-op).
 */
void uac2_release_interface(libusb_device_handle *handle, int interface_number);

