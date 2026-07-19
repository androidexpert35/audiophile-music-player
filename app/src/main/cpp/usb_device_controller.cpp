// ─────────────────────────────────────────────────────────────────────────────
// usb_device_controller.cpp
//
// Device-control layer — implementation.
//
// Executes the three ordered sub-operations that transform a raw libusb device
// handle (Step 1) into an interface that is exclusively owned by this process
// and has its isochronous OUT endpoint active and ready to receive transfers:
//
//   Phase 1 — libusb_set_auto_detach_kernel_driver(handle, 1)
//   Phase 2 — libusb_claim_interface(handle, iface_num)
//   Phase 3 — libusb_set_interface_alt_setting(handle, iface_num, alt_setting)
//
// See usb_device_controller.h for the full explanation of Android-specific
// error conditions, kernel-driver conflicts, and rollback semantics.
// ─────────────────────────────────────────────────────────────────────────────

#include "usb_device_controller.h"

#include <android/log.h>
#include <unistd.h>   // usleep — required for the post-clear-halt settling delay

#include "libusb/libusb.h"

// ─── Logging macros ───────────────────────────────────────────────────────────

static constexpr const char *CTRL_TAG = "UsbDeviceCtrl";

#define CLOGD(...) __android_log_print(ANDROID_LOG_DEBUG, CTRL_TAG, __VA_ARGS__)
#define CLOGI(...) __android_log_print(ANDROID_LOG_INFO,  CTRL_TAG, __VA_ARGS__)
#define CLOGW(...) __android_log_print(ANDROID_LOG_WARN,  CTRL_TAG, __VA_ARGS__)
#define CLOGE(...) __android_log_print(ANDROID_LOG_ERROR, CTRL_TAG, __VA_ARGS__)

// ─── Internal helper: map libusb claim error → UsbControlStatus ───────────────

/**
 * Translate a negative libusb error code from libusb_claim_interface() into
 * the corresponding UsbControlStatus value.
 *
 * This centralises the mapping logic so the main function body reads as a
 * clean control-flow sequence rather than a nested switch.
 *
 * @param libusb_ret  Negative return value from libusb_claim_interface().
 * @return            Specific UsbControlStatus for the failure reason.
 */
static UsbControlStatus map_claim_error(int libusb_ret) noexcept {
    switch (libusb_ret) {
        case LIBUSB_ERROR_BUSY:
            // The most critical Android-specific failure path.
            //
            // On Android, LIBUSB_ERROR_BUSY at claim time means the kernel UAC2
            // driver (snd-usb-audio compiled into the OEM kernel, or a vendor
            // audio HAL driver) has already bound to this interface.
            //
            // libusb_set_auto_detach_kernel_driver() is ineffective here because
            // Android prohibits USBDEVFS_DISCONNECT from non-root processes.
            // If auto-detach did work it would forcibly unbind snd-usb-audio,
            // which would also silence any other audio that was routing through
            // that DAC via AudioFlinger — a destructive side-effect unacceptable
            // in a music-player context.
            //
            // Recovery: the Kotlin layer must detect ClaimBusyKernelDriverOwner
            // and route audio through AudioFlinger / AudioTrack instead.
            return UsbControlStatus::ClaimBusyKernelDriverOwner;

        case LIBUSB_ERROR_NO_DEVICE:
            // USB cable was physically removed between Step 1 and Step 3.
            // This is a transient hardware event; the Kotlin layer should re-probe.
            return UsbControlStatus::ClaimDeviceDisconnected;

        case LIBUSB_ERROR_NOT_FOUND:
            // The interface_number from the Step 2 scanner does not exist on the
            // device's active configuration.  This should be impossible if the
            // descriptor scan was correct, but guard it explicitly to avoid
            // silent undefined behaviour further down the pipeline.
            return UsbControlStatus::ClaimInterfaceNotFound;

        case LIBUSB_ERROR_ACCESS:
            // The Java UsbDeviceConnection FD may have been revoked or the
            // UsbManager permission was withdrawn after Step 1 succeeded.
            return UsbControlStatus::ClaimAccessDenied;

        default:
            // LIBUSB_ERROR_IO, LIBUSB_ERROR_OVERFLOW, and other unexpected codes
            // are all bucket-caught here; the raw code is exposed via
            // UsbInterfaceClaimResult::libusb_error_code for further diagnosis.
            return UsbControlStatus::ClaimUnexpectedError;
    }
}

// ─── Internal helper: map libusb alt-setting error → UsbControlStatus ─────────

/**
 * Translate a negative libusb error code from libusb_set_interface_alt_setting()
 * into the corresponding UsbControlStatus value.
 *
 * @param libusb_ret  Negative return value from libusb_set_interface_alt_setting().
 * @return            Specific UsbControlStatus for the failure reason.
 */
static UsbControlStatus map_alt_setting_error(int libusb_ret) noexcept {
    switch (libusb_ret) {
        case LIBUSB_ERROR_TIMEOUT:
            // The SET_INTERFACE control transfer to the DAC timed out.
            // Likely cause: the DAC firmware is hung or the cable quality is
            // poor.  The interface is still claimed but the endpoint is inactive;
            // the caller MUST release the interface before re-trying or giving up.
            return UsbControlStatus::AltSettingTimeout;

        case LIBUSB_ERROR_PIPE:
            // STALL handshake received on the control pipe EP0.
            // The DAC rejected SET_INTERFACE — firmware bug or invalid alt index.
            // The interface must be released and the DAC may need a reset.
            return UsbControlStatus::AltSettingStalled;

        case LIBUSB_ERROR_NO_DEVICE:
            return UsbControlStatus::AltSettingDeviceDisconnected;

        default:
            return UsbControlStatus::AltSettingUnexpectedError;
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Main implementation
// ─────────────────────────────────────────────────────────────────────────────

UsbInterfaceClaimResult uac2_claim_and_activate_interface(
        libusb_device_handle            *handle,
        const Uac2StreamingEndpointInfo &endpoint)
{
    UsbInterfaceClaimResult result;

    // Defensive null-guard.  The caller (JNI bridge) should never pass null,
    // but a hard crash inside a JANi callback is worse than a logged failure.
    if (handle == nullptr) {
        CLOGE("uac2_claim_and_activate_interface: null device handle — aborting");
        result.status           = UsbControlStatus::ClaimUnexpectedError;
        result.libusb_error_code = LIBUSB_ERROR_INVALID_PARAM;
        return result;
    }

    const int iface_num  = static_cast<int>(endpoint.interface_number);
    const int alt_num    = static_cast<int>(endpoint.alt_setting);
    const int ep_addr    = static_cast<int>(endpoint.endpoint_address);

    CLOGI("uac2_claim_and_activate_interface: iface=%d alt=%d ep=0x%02X effective=%u bytes/µframe",
          iface_num, alt_num, ep_addr, endpoint.effective_bytes_per_uframe);

    // ── Phase 1: Request auto-detach of any resident kernel driver ────────────
    //
    // libusb_set_auto_detach_kernel_driver() arms a per-handle flag that tells
    // libusb to automatically call USBDEVFS_DISCONNECT on the kernel before
    // libusb_claim_interface() and USBDEVFS_CONNECT after libusb_release_interface().
    //
    // On Android this syscall path is blocked:
    //   • USBDEVFS_DISCONNECT requires CAP_SYS_RAWIO or a root UID.
    //   • Android's SELinux policy denies it for app processes.
    //   • Result: LIBUSB_ERROR_NOT_SUPPORTED (~100% of Android devices).
    //
    // The function is still called unconditionally because:
    //   a) On rooted devices or custom ROMs it MAY succeed and silently detach
    //      snd-usb-audio, enabling the claim to proceed.
    //   b) The flag persists on the handle; if future Android versions relax the
    //      permission model this code will benefit without change.
    //   c) The LIBUSB_ERROR_NOT_SUPPORTED case is explicitly documented and the
    //      code continues — failure here is not a driver bug, it's a platform
    //      policy boundary.
    {
        const int auto_detach_ret = libusb_set_auto_detach_kernel_driver(handle, 1);

        if (auto_detach_ret == LIBUSB_SUCCESS) {
            // Rare on stock Android; normal on rooted devices / desktop Linux.
            CLOGI("  Phase 1 [auto-detach]: SUCCESS — kernel driver will be detached on claim");
            result.auto_detach_supported = true;

        } else if (auto_detach_ret == LIBUSB_ERROR_NOT_SUPPORTED) {
            // Expected path on ~99% of Android devices.  Non-fatal — log at INFO
            // so the developer knows the platform limitation is active, but do
            // NOT abort.  The LIBUSB_ERROR_BUSY outcome of Phase 2 (if it occurs)
            // will tell the complete story.
            CLOGI("  Phase 1 [auto-detach]: LIBUSB_ERROR_NOT_SUPPORTED "
                  "(expected on Android — kernel driver detach is blocked by SELinux; "
                  "proceeding to claim attempt)");
            result.auto_detach_supported = false;
            // Record in status only when no later phase overrides it.
            result.status = UsbControlStatus::AutoDetachNotSupported;

        } else {
            // Unexpected non-support error (e.g., LIBUSB_ERROR_NO_DEVICE if the
            // device just disconnected).  Still non-fatal for Phase 1 — the claim
            // attempt will surface the actual device state.
            CLOGW("  Phase 1 [auto-detach]: unexpected error — %s (%d) "
                  "(non-fatal; proceeding to claim)",
                  libusb_error_name(auto_detach_ret), auto_detach_ret);
            result.auto_detach_supported    = false;
            result.status                   = UsbControlStatus::AutoDetachUnexpectedError;
            result.libusb_error_code        = auto_detach_ret;
        }
    }

    // ── Phase 2: Claim exclusive ownership of the AudioStreaming interface ─────
    //
    // libusb_claim_interface() issues USBDEVFS_CLAIMINTERFACE via ioctl on the
    // device's file descriptor.  On success the kernel marks the interface as
    // exclusively owned by this file descriptor; no other process (including
    // snd-usb-audio) can re-claim it until we release it.
    //
    // The critical Android failure mode is LIBUSB_ERROR_BUSY:
    //   If the kernel UAC2 driver bound to this interface before our FD was
    //   opened, the ioctl returns EBUSY.  auto-detach cannot help because
    //   Android blocks USBDEVFS_DISCONNECT for app processes (Phase 1 above).
    //
    //   When LIBUSB_ERROR_BUSY is returned the driver is in a catch-22:
    //     - We cannot claim without detaching.
    //     - We cannot detach without root.
    //   => The Kotlin layer MUST fall back to AudioFlinger / AudioTrack.
    {
        const int claim_ret = libusb_claim_interface(handle, iface_num);

        if (claim_ret != LIBUSB_SUCCESS) {
            // Map to a specific status so the Kotlin layer gets a crisp reason
            // without parsing a raw libusb integer.
            const UsbControlStatus claim_status = map_claim_error(claim_ret);

            // Special-case the BUSY path with a long, developer-actionable message
            // because it is the most common failure mode and requires a deliberate
            // fallback decision.
            if (claim_ret == LIBUSB_ERROR_BUSY) {
                CLOGE("  Phase 2 [claim iface=%d]: LIBUSB_ERROR_BUSY — "
                      "the kernel UAC2 driver (snd-usb-audio or OEM equivalent) owns "
                      "this interface.  auto-detach is blocked on Android (SELinux).  "
                      "Kotlin layer must fall back to AudioFlinger/AudioTrack.",
                      iface_num);
            } else {
                CLOGE("  Phase 2 [claim iface=%d]: FAILED — %s (%d)",
                      iface_num, libusb_error_name(claim_ret), claim_ret);
            }

            // No rollback needed — nothing was claimed.
            result.success           = false;
            result.status            = claim_status;
            result.libusb_error_code = claim_ret;
            return result;
        }

        CLOGI("  Phase 2 [claim iface=%d]: SUCCESS — interface exclusively owned by this process",
              iface_num);
        // Record the claimed interface number so the caller knows what to release.
        result.claimed_interface_number = iface_num;
    }

    // ── Phase 3: Activate the isochronous alternate setting ───────────────────
    //
    // libusb_set_interface_alt_setting() issues a USB SET_INTERFACE control
    // transfer to EP0 of the device.  The DAC responds by:
    //   a) Activating the isochronous OUT endpoint on the requested alt setting.
    //   b) Allocating its internal USB transaction scheduler resources.
    //
    // This MUST succeed before any isochronous OUT transfer can be submitted.
    // Failure here means the endpoint is not ready to accept audio data even
    // though we hold the interface claim.
    //
    // Rollback contract: if this phase fails we MUST release the interface
    // (libusb_release_interface) so the interface is not left orphan-claimed
    // with an inactive endpoint.  Failing to release would lock out any future
    // claim attempt (including the AudioFlinger fallback path) until the device
    // is physically reconnected.
    {
        const int alt_ret = libusb_set_interface_alt_setting(handle, iface_num, alt_num);

        if (alt_ret != LIBUSB_SUCCESS) {
            CLOGE("  Phase 3 [set alt iface=%d alt=%d]: FAILED — %s (%d)",
                  iface_num, alt_num, libusb_error_name(alt_ret), alt_ret);

            // ── ROLLBACK: release the interface we claimed in Phase 2 ─────────
            //
            // If we returned without releasing, the kernel would keep the claim
            // alive for the lifetime of the FD — which is the UsbDeviceConnection
            // in Java.  Any subsequent libusb_claim_interface() on the same handle
            // (e.g., after a driver reset attempt) would return LIBUSB_ERROR_BUSY
            // against our own earlier claim.  Explicit release keeps the state
            // machine deterministic.
            const int release_ret = libusb_release_interface(handle, iface_num);
            if (release_ret != LIBUSB_SUCCESS) {
                // Log the rollback failure but don't mask the original alt-setting
                // error — the original error is the root cause.
                CLOGW("  Phase 3 rollback [release iface=%d]: failed — %s (%d) "
                      "(FD may already be in inconsistent state)",
                      iface_num, libusb_error_name(release_ret), release_ret);
            } else {
                CLOGD("  Phase 3 rollback [release iface=%d]: OK — interface released cleanly",
                      iface_num);
            }

            result.success                  = false;
            result.status                   = map_alt_setting_error(alt_ret);
            result.libusb_error_code        = alt_ret;
            // Reset to -1: the claim no longer holds after the rollback release.
            result.claimed_interface_number = -1;
            return result;
        }

        CLOGI("  Phase 3 [set alt iface=%d alt=%d]: SUCCESS — isochronous endpoint 0x%02X is active",
              iface_num, alt_num, ep_addr);
        result.active_alt_setting = alt_num;
    }

    // ── All phases passed ─────────────────────────────────────────────────────
    result.success = true;
    result.status  = UsbControlStatus::Success;
    // libusb_error_code remains 0 (LIBUSB_SUCCESS).

    CLOGI("uac2_claim_and_activate_interface: COMPLETE — "
          "iface=%d alt=%d ep=0x%02X ready for isochronous OUT transfers",
          iface_num, alt_num, ep_addr);

    return result;
}

// ─────────────────────────────────────────────────────────────────────────────
// uac2_claim_interface_only — Phase 1 + Phase 2 only (no alt-setting)
// ─────────────────────────────────────────────────────────────────────────────

UsbInterfaceClaimResult uac2_claim_interface_only(
        libusb_device_handle            *handle,
        const Uac2StreamingEndpointInfo &endpoint)
{
    UsbInterfaceClaimResult result;

    if (handle == nullptr) {
        CLOGE("uac2_claim_interface_only: null device handle — aborting");
        result.status            = UsbControlStatus::ClaimUnexpectedError;
        result.libusb_error_code = LIBUSB_ERROR_INVALID_PARAM;
        return result;
    }

    const int iface_num = static_cast<int>(endpoint.interface_number);
    const int alt_num   = static_cast<int>(endpoint.alt_setting);   // stored, but NOT activated here
    const int ep_addr   = static_cast<int>(endpoint.endpoint_address);

    CLOGI("uac2_claim_interface_only: iface=%d (target alt=%d ep=0x%02X) — "
          "Phase 1+2 only; alt-setting will be applied AFTER clock setup",
          iface_num, alt_num, ep_addr);

    // ── Phase 1: auto-detach (best-effort, non-fatal — identical to full fn) ─
    {
        const int auto_detach_ret = libusb_set_auto_detach_kernel_driver(handle, 1);
        if (auto_detach_ret == LIBUSB_SUCCESS) {
            CLOGI("  Phase 1 [auto-detach]: SUCCESS");
            result.auto_detach_supported = true;
        } else if (auto_detach_ret == LIBUSB_ERROR_NOT_SUPPORTED) {
            CLOGI("  Phase 1 [auto-detach]: NOT_SUPPORTED (expected on Android)");
            result.auto_detach_supported = false;
            result.status = UsbControlStatus::AutoDetachNotSupported;
        } else {
            CLOGW("  Phase 1 [auto-detach]: unexpected %s (%d) — continuing",
                  libusb_error_name(auto_detach_ret), auto_detach_ret);
            result.auto_detach_supported = false;
            result.status                = UsbControlStatus::AutoDetachUnexpectedError;
            result.libusb_error_code     = auto_detach_ret;
        }
    }

    // ── Phase 2: claim streaming interface ───────────────────────────────────
    {
        const int claim_ret = libusb_claim_interface(handle, iface_num);
        if (claim_ret != LIBUSB_SUCCESS) {
            const UsbControlStatus claim_status = map_claim_error(claim_ret);
            if (claim_ret == LIBUSB_ERROR_BUSY) {
                CLOGE("  Phase 2 [claim iface=%d]: LIBUSB_ERROR_BUSY — "
                      "kernel UAC2 driver owns this interface; "
                      "Kotlin layer must fall back to AudioFlinger.",
                      iface_num);
            } else {
                CLOGE("  Phase 2 [claim iface=%d]: FAILED — %s (%d)",
                      iface_num, libusb_error_name(claim_ret), claim_ret);
            }
            result.success           = false;
            result.status            = claim_status;
            result.libusb_error_code = claim_ret;
            return result;
        }
        CLOGI("  Phase 2 [claim iface=%d]: SUCCESS — "
              "streaming interface exclusively owned; "
              "Phase 3 (alt-setting) deferred until after clock setup",
              iface_num);
        result.claimed_interface_number = iface_num;
    }

    // Phase 3 deliberately skipped — will be applied by uac2_apply_alt_setting()
    // after the UAC2 Clock Source SET_CUR transfer.
    result.success          = true;
    result.status           = UsbControlStatus::Success;
    result.active_alt_setting = -1;   // not yet activated

    CLOGI("uac2_claim_interface_only: COMPLETE — "
          "iface=%d claimed, alt-setting NOT yet activated", iface_num);
    return result;
}

// ─────────────────────────────────────────────────────────────────────────────
// uac2_apply_alt_setting — Phase 3 standalone
// ─────────────────────────────────────────────────────────────────────────────

UsbControlStatus uac2_apply_alt_setting(
        libusb_device_handle *handle,
        int                   iface_num,
        int                   alt_num,
        int                   endpoint_address)
{
    if (handle == nullptr) {
        CLOGE("uac2_apply_alt_setting: null handle — aborting");
        return UsbControlStatus::AltSettingUnexpectedError;
    }

    // ── Hard reset: Alt 0 → Alt N ("0-to-N jump" pattern) ────────────────────
    //
    // Many XMOS/FiiO/Savitech firmware variants require the host to first drive
    // the interface back to Alt 0 (zero-bandwidth idle) before jumping to the
    // high-bandwidth DSD alt setting.  Without this "0→N" transition the DAC's
    // internal isochronous buffer may retain stale state from a prior session,
    // causing LIBUSB_ERROR_BUSY (-6) on the very first transfer submission.
    //
    // The FiiO KA5 XMOS core specifically requires this sequence:
    //   SET_INTERFACE iface=N alt=0   ← quiesce / clear internal ISO buffer
    //   SET_INTERFACE iface=N alt=M   ← open the target high-bandwidth alt
    //
    // The return value of the reset call is intentionally NOT checked as a
    // hard error: if the interface is already at alt 0 or the firmware treats
    // two consecutive SET_INTERFACE calls as idempotent, any non-SUCCESS return
    // is a harmless diagnostic artefact, not a protocol violation.
    if (alt_num != 0) {
        CLOGI("uac2_apply_alt_setting: hard-reset — "
              "SET_INTERFACE iface=%d alt=0 (idle) before activating alt=%d",
              iface_num, alt_num);
        const int reset_ret = libusb_set_interface_alt_setting(handle, iface_num, 0);
        if (reset_ret != LIBUSB_SUCCESS) {
            CLOGW("uac2_apply_alt_setting: SET_INTERFACE alt=0 returned %s (%d) — "
                  "non-fatal; proceeding to alt=%d "
                  "(firmware may have already idled or alt=0 is unsupported)",
                  libusb_error_name(reset_ret), reset_ret, alt_num);
        } else {
            CLOGD("uac2_apply_alt_setting: SET_INTERFACE alt=0 (idle) OK — "
                  "DAC internal ISO buffer quiesced; proceeding to alt=%d",
                  alt_num);
        }
    }

    CLOGI("uac2_apply_alt_setting: SET_INTERFACE iface=%d alt=%d ...",
          iface_num, alt_num);

    const int alt_ret = libusb_set_interface_alt_setting(handle, iface_num, alt_num);

    if (alt_ret != LIBUSB_SUCCESS) {
        // NOTE: unlike uac2_claim_and_activate_interface(), we do NOT release
        // the interface here.  The caller (nativeActivateAltSetting) owns the
        // claim and is responsible for deciding whether to retry or release.
        CLOGE("uac2_apply_alt_setting: FAILED — iface=%d alt=%d  %s (%d)",
              iface_num, alt_num, libusb_error_name(alt_ret), alt_ret);
        return map_alt_setting_error(alt_ret);
    }

    CLOGI("uac2_apply_alt_setting: SUCCESS — "
          "iface=%d alt=%d ISO endpoint is now active", iface_num, alt_num);

    // ── Phase 3.5: Clear any hardware HALT on the ISO OUT endpoint ────────────
    //
    // Root cause of USB deadlock on FiiO KA5 (XMOS chip):
    //
    //   After the Alt 0 → Alt N transition the XMOS USB receiver can silently
    //   assert a HALT/STALL condition on the isochronous OUT endpoint.  The
    //   condition is NOT reported via any libusb error code — transfer
    //   submissions succeed and libusb_submit_transfer() returns 0, but the
    //   isochronous callbacks never fire.  This produces the characteristic
    //   deadlock fingerprint:
    //       total_pushed=128 KB   ring_free=0 KB   full_sleeps=3983
    //   The ring buffer fills because the producer (PCM decoder) keeps pushing
    //   data, but the consumer (isochronous completion callback) never drains it.
    //
    //   libusb_clear_halt() issues a USB CLEAR_FEATURE(ENDPOINT_HALT) control
    //   transfer to EP0, which instructs the device to release the HALT condition
    //   and re-arm its internal ISO receiver.  This must happen:
    //     • AFTER SET_INTERFACE activates the alt setting (so the endpoint exists)
    //     • BEFORE the transfer pool is allocated and submissions begin
    //
    //   The 10 ms usleep gives the XMOS DSP time to flush its endpoint FIFO
    //   and complete any internal state-machine transition before the first
    //   isochronous OUT transfer arrives.
    //
    // Only applies when activating a live alt setting (alt_num > 0); the Alt-0
    // idle/cold-reset path has no isochronous endpoint to clear.
    if (alt_num != 0 && endpoint_address != 0) {
        const unsigned char ep = static_cast<unsigned char>(endpoint_address);

        CLOGI("uac2_apply_alt_setting: clearing endpoint HALT on ep=0x%02X "
              "(XMOS deadlock prevention — CLEAR_FEATURE(ENDPOINT_HALT))", ep);

        const int halt_ret = libusb_clear_halt(handle, ep);

        if (halt_ret == LIBUSB_SUCCESS) {
            CLOGI("uac2_apply_alt_setting: libusb_clear_halt(ep=0x%02X) SUCCESS — "
                  "ISO endpoint HALT cleared; XMOS receiver re-armed", ep);
        } else if (halt_ret == LIBUSB_ERROR_NOT_FOUND) {
            // LIBUSB_ERROR_NOT_FOUND means the endpoint was not halted —
            // the XMOS chip was already ready.  Non-fatal; log at debug level
            // so it doesn't pollute normal-path logs.
            CLOGD("uac2_apply_alt_setting: libusb_clear_halt(ep=0x%02X) → "
                  "LIBUSB_ERROR_NOT_FOUND (endpoint not halted — already armed; OK)",
                  ep);
        } else {
            // Any other return is unexpected but non-fatal.  The ISO path may
            // still work; log at WARN so the developer can correlate with
            // subsequent transfer behaviour.
            CLOGW("uac2_apply_alt_setting: libusb_clear_halt(ep=0x%02X) returned "
                  "%s (%d) — non-fatal; transfer submission will proceed",
                  ep, libusb_error_name(halt_ret), halt_ret);
        }

        // Allow the XMOS DSP 10 ms to flush its internal endpoint FIFO and
        // complete the post-CLEAR_FEATURE state-machine transition.
        // Without this pause the first isochronous submission may arrive while
        // the chip is mid-reset, causing the callbacks to stall again.
        CLOGD("uac2_apply_alt_setting: usleep(10 ms) — XMOS buffer flush delay");
        usleep(10000);
        CLOGD("uac2_apply_alt_setting: delay complete — ISO endpoint ready for transfers");
    }

    return UsbControlStatus::Success;
}

// ─────────────────────────────────────────────────────────────────────────────
// Interface release
// ─────────────────────────────────────────────────────────────────────────────

void uac2_release_interface(libusb_device_handle *handle, int interface_number)
{
    // Safe no-op guard: the claim may never have succeeded
    // (e.g., ClaimBusyKernelDriverOwner path), or the Kotlin layer may call
    // release unconditionally regardless of whether init completed.
    if (handle == nullptr || interface_number < 0) {
        CLOGD("uac2_release_interface: no-op (handle=%p iface=%d)",
              static_cast<void *>(handle), interface_number);
        return;
    }

    CLOGI("uac2_release_interface: beginning teardown for iface=%d", interface_number);

    // ── Courtesy: deactivate the isochronous endpoint by switching to alt 0 ───
    //
    // Sending SET_INTERFACE 0 before release tells the DAC to quiesce its
    // isochronous endpoint scheduler and enter low-power idle.
    //
    // This is a polite host-side convention from the UAC2 Class Definition
    // §4.9.1: "The host shall not activate the zero-bandwidth interface when
    // audio is playing, but SHALL activate it when streaming is complete."
    //
    // Failure is non-fatal and must not prevent the claim release below:
    //   • LIBUSB_ERROR_NO_DEVICE → cable already removed; release proceeds.
    //   • LIBUSB_ERROR_TIMEOUT   → DAC unresponsive; release still cleans up the
    //                              kernel-side claim even if the device is silent.
    {
        const int alt_ret = libusb_set_interface_alt_setting(handle, interface_number, 0);
        if (alt_ret == LIBUSB_SUCCESS) {
            CLOGD("  set alt=%d→0: OK (isochronous endpoint deactivated)", interface_number);
        } else if (alt_ret == LIBUSB_ERROR_NO_DEVICE) {
            // Device already gone — skip without alarming the log.
            CLOGD("  set alt→0: LIBUSB_ERROR_NO_DEVICE (device already disconnected — skipping)");
        } else {
            // Log but never abort; the release below is mandatory regardless.
            CLOGW("  set alt→0 [iface=%d]: failed — %s (%d) (non-fatal; proceeding with release)",
                  interface_number, libusb_error_name(alt_ret), alt_ret);
        }
    }

    // ── Mandatory: release the interface claim ────────────────────────────────
    //
    // libusb_release_interface() issues USBDEVFS_RELEASEINTERFACE, which:
    //   a) Releases kernel-side exclusive ownership so snd-usb-audio or the
    //      next FD owner can reclaim the interface if needed.
    //   b) Cancels any pending isochronous transactions scheduled on this
    //      interface (though by this point all transfers MUST already be
    //      cancelled and freed — see Step 4 teardown contract).
    //
    // If auto-detach was armed (Phase 1 succeeded), libusb will automatically
    // call USBDEVFS_CONNECT here to re-attach the kernel driver — restoring the
    // device to normal OS-controlled operation after our exclusive session ends.
    {
        const int release_ret = libusb_release_interface(handle, interface_number);
        if (release_ret == LIBUSB_SUCCESS) {
            CLOGI("uac2_release_interface: iface=%d released — kernel driver may re-attach",
                  interface_number);
        } else if (release_ret == LIBUSB_ERROR_NO_DEVICE) {
            // Device is already gone; the kernel already cleaned up the claim.
            CLOGI("uac2_release_interface: LIBUSB_ERROR_NO_DEVICE — device disconnected; "
                  "kernel has already cleaned up iface=%d", interface_number);
        } else {
            // Failure here leaves a stale claim in the kernel; the device will
            // likely need to be physically unplugged and reconnected.  Log it
            // clearly.
            CLOGE("uac2_release_interface: libusb_release_interface(iface=%d) FAILED — %s (%d) "
                  "(stale claim may persist until device reconnect)",
                  interface_number, libusb_error_name(release_ret), release_ret);
        }
    }
}

