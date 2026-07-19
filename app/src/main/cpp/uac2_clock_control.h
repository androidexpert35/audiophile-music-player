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
 * @return bClockID on success, or -1 when not found / on any libusb error.
 */
int uac2_find_clock_source_id(libusb_device_handle *handle) noexcept;

/**
 * Programs a UAC2 Clock Source by cycling through a ranked candidate list of
 * bClockID values until the DAC acknowledges the SET_CUR request.
 *
 * Needed because some chips (XMOS / Savitech, e.g. FiiO KA series) misreport
 * or hide the bClockID in their descriptors across firmware revisions; probing
 * the descriptor-parsed ID first and then the known-in-the-wild fallbacks
 * guarantees a successful transfer regardless of firmware version.
 *
 * Candidate ranking: parsed descriptor ID, then 41 (FiiO KA early firmware),
 * 40 (QCC5100 designs), 10/11/12 (Cirrus/TI entity numbering), 1 (UAC2
 * reference default: XMOS/ESS, FiiO KA5 production firmware).
 *
 * The Audio Control interface is assumed at interface 0, which holds for all
 * single-function UAC2 DACs (USB Audio spec §3.4); multi-function devices are
 * handled by the caller's explicit-interface fallback.
 *
 * @param handle          Open libusb device handle.
 * @param parsed_clock_id bClockID from a descriptor scan, or ≤ 0 if unknown.
 * @param sample_rate_hz  Target sample rate in Hz.
 * @param winning_id_out  Optional out-param receiving the acknowledged bClockID
 *                        (needed later by the DSD teardown soft-reset — sending
 *                        SET_CUR to an unrecognised ID stalls the control
 *                        endpoint with LIBUSB_ERROR_PIPE).
 * @return                4 (bytes transferred) on success; -1 when every
 *                        candidate is exhausted.
 */
int uac2_force_clock_sample_rate(
        libusb_device_handle *handle,
        int parsed_clock_id,
        uint32_t sample_rate_hz,
        int *winning_id_out = nullptr) noexcept;
