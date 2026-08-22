#pragma once

#include <cstdint>

struct libusb_device_handle;

// ─────────────────────────────────────────────────────────────────────────────
// UAC2 Clock Source control (UAC2 §5.2.5.1 / §5.2.1)
//
// Selecting an alternate setting only routes isochronous bandwidth — it does
// NOT lock the DAC's internal PLL to the stream rate.  The host MUST program
// the frequency via a SET_CUR request to the Clock Source entity's
// CS_SAM_FREQ_CONTROL attribute, otherwise the DAC keeps its power-on default
// rate and mutes its output (the "FSR ERROR" shown by e.g. the FiiO KA5).
//
// This module owns every encoder of that control transfer so the wire format
// cannot diverge between session setup, brute-force probing, and the DSD
// teardown soft-reset.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Programs one validated UAC2 Clock Source with a four-byte SET_CUR rate.
 *
 * bmRequestType is 0x21 (Host→Device | Class | Interface): the Clock Source
 * entity lives on the Audio Control INTERFACE (UAC2 §5.2.1 Table 5-2), and
 * strict firmwares (FiiO, XMOS, ESS) STALL the endpoint-recipient encoding
 * (0x22).
 *
 * @return ≥ 0 (bytes transferred) on success; negative libusb error code.
 */
int uac2_set_clock_sample_rate(
        libusb_device_handle *handle,
        uint8_t control_interface,
        uint8_t clock_source_id,
        uint32_t sample_rate_hz) noexcept;

/**
 * Scans the active configuration descriptor for a UAC2 CLOCK_SOURCE entity
 * (CS_INTERFACE / subtype 0x0A inside an Audio Control interface) and returns
 * its bClockID.
 *
 * The same walk also discovers **which interface number carries the Audio
 * Control function**. On single-function DACs that is interface 0 (USB Audio
 * spec §3.4), but composite devices — Bluetooth/USB combos such as the HiBy W4
 * (QCC5181 + audio), where the SoC exposes vendor/HID interfaces first — may
 * enumerate Audio Control at a non-zero index. Class requests addressed to the
 * wrong interface are rejected at best; at worst they reach a vendor interface
 * of the companion chip, which field reports link to firmware crashes and
 * device shutdowns.
 *
 * @param handle           Open libusb device handle.
 * @param ac_interface_out Optional out-param receiving the bInterfaceNumber of
 *                         the first Audio Control interface encountered, or -1
 *                         when none exists. Populated even when no CLOCK_SOURCE
 *                         descriptor is found.
 * @return bClockID on success, or -1 when not found / on any libusb error.
 */
int uac2_find_clock_source_id(
        libusb_device_handle *handle,
        int *ac_interface_out = nullptr) noexcept;

/**
 * Programs a UAC2 Clock Source, trying the descriptor-parsed bClockID first
 * and then every OTHER Clock Source entity the configuration descriptor
 * declares. The UAC2 reference default (bClockID=1) is used only when the
 * descriptor walk finds no Clock Source at all.
 *
 * Speculative IDs (41, 40, 10, 11, 12) were deliberately REMOVED from the
 * candidate list, and a bare 1 is never appended behind a successfully parsed
 * ID: a SET_CUR addressed to a clock entity the firmware does not expose
 * STALLs EP0, and on XMOS/FiiO firmware that stall wedges the control pipe
 * **for the remainder of the session** — every later SET_CUR (including IDs
 * that would have worked) then fails with LIBUSB_ERROR_PIPE until the DAC is
 * physically re-plugged. See the Step-3 commentary in usb_teardown.cpp, where
 * the same field failure forced the identical fix on the teardown path.
 * Combined with a non-fatal caller this produced the field bug where rapid
 * track skips left the DAC PLL at the previous rate (audible distortion until
 * re-plug). Enumerating the *declared* entities keeps multi-clock DACs (an
 * internal PLL plus an S/PDIF or word-clock source, where the first descriptor
 * is not the one feeding the USB stream) covered at none of that risk.
 *
 * Every candidate is attempted several times with a short pause in between, so
 * a DAC that is merely mid PLL re-lock is not mistaken for one that refuses
 * the rate — a distinction the caller cannot recover from, since it abandons
 * the direct-USB session and falls back to the resampling platform mixer.
 *
 * @param handle            Open libusb device handle.
 * @param control_interface bInterfaceNumber of the Audio Control interface
 *                          (from uac2_find_clock_source_id's ac_interface_out).
 * @param parsed_clock_id   bClockID from a descriptor scan, or ≤ 0 if unknown.
 * @param sample_rate_hz    Target sample rate in Hz.
 * @param winning_id_out    Optional out-param receiving the acknowledged
 *                          bClockID (needed later by the DSD teardown
 *                          soft-reset).
 * @return                  4 (bytes transferred) on success; -1 when every
 *                          candidate is exhausted.
 */
int uac2_force_clock_sample_rate(
        libusb_device_handle *handle,
        uint8_t control_interface,
        int parsed_clock_id,
        uint32_t sample_rate_hz,
        int *winning_id_out = nullptr) noexcept;
