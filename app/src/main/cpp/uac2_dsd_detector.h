// ─────────────────────────────────────────────────────────────────────────────
// uac2_dsd_detector.h
//
// Step 11 — Native DSD capability detection for UAC2 devices.
//
// Analyses the list of streaming endpoints already scanned by Step 2
// (uac2_find_streaming_endpoints) and classifies each alternate setting's DSD
// transport capability without issuing any additional USB control transfers.
//
// ── DSD transport background ──────────────────────────────────────────────────
//
// DSD (Direct Stream Digital) audio can be delivered to a USB DAC via two
// distinct transport mechanisms:
//
//   1. DoP — DSD over PCM (IEC 61937-style wrapper)
//      DSD bits are encoded in the MSBs of 24-bit or 32-bit PCM words with
//      alternating DoP marker bytes (0x05 / 0xFA).  The USB host delivers a
//      standard TYPE_I PCM isochronous stream; the DAC detects the DoP markers
//      and extracts the DSD bitstream internally.  No special descriptor flag
//      is needed — any TYPE_I alt setting with bSubslotSize ≥ 3 can carry DoP.
//
//   2. Native DSD (Raw Data)
//      The actual DSD bitstream (1-bit 2.8224 MHz / 5.6448 MHz / 11.2896 MHz
//      etc.) is packed directly into the USB transfer as raw bytes, with no PCM
//      framing.  This is identified by two complementary descriptor signals:
//
//        a) bFormatType == 0x04 (FORMAT_TYPE_IV) in the AS_GENERAL descriptor
//           [ADF2 §2.3.4] — the definitive UAC2 native DSD indicator.
//           Confirmed by a matching FORMAT_TYPE_IV descriptor (4 bytes) in
//           the interface's extra[] blob.
//
//        b) bmFormats bit D31 == 1 (UAC_FORMAT_TYPE_I_RAW_DATA = 0x80000000)
//           in the AS_GENERAL descriptor when bFormatType == 0x01 (TYPE_I).
//           [ADF2 §2.3.1, Table 2-1]  A minority of DACs advertise native DSD
//           this way by setting the RAW_DATA bit in a TYPE_I alt setting.
//
//      A non-conformant heuristic is also applied: some DAC firmware versions
//      set the combined PCM sub-format bits (0x1F) to signal raw capability
//      in a TYPE_I setting.  This is checked as a secondary indicator.
//
// ── Detection priority ────────────────────────────────────────────────────────
//
// When multiple alt settings qualify the strongest transport is preferred:
//
//   NativeTypeIV  > NativeTypeIRaw > DopOnly > None
//
// For same-priority alt settings the one with the greatest effective bandwidth
// (effective_bytes_per_uframe) is selected; ties are broken by the lowest
// (interface_number, alt_setting) pair for determinism.
//
// ── DAC examples ─────────────────────────────────────────────────────────────
//
//   FiiO K9 Pro          — exposes alt 2 = PCM, alt 3 = DSD (FORMAT_TYPE_IV)
//   RME ADI-2 DAC FS     — TYPE_IV on a dedicated DSD alt setting
//   iFi Hip-DAC 3        — TYPE_I bmFormats RAW_DATA bit on the high-rate alt
//   Topping DX3 Pro+     — FORMAT_TYPE_IV, async sync type
//   Generic UAC2 DAC     — TYPE_I only; DoP must be used at the software level
//
// ─────────────────────────────────────────────────────────────────────────────

#pragma once

#include <cstdint>
#include <vector>

#include "uac2_descriptor_parser.h"   // Uac2StreamingEndpointInfo, Uac2AsGeneralInfo

// ─────────────────────────────────────────────────────────────────────────────
// DSD transport classification
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Describes the DSD transport capability of a single UAC2 alternate setting.
 *
 * Ordered by ascending strength so direct integer comparison gives priority:
 *   None < DopOnly < NativeTypeIRaw < NativeTypeIV
 */
enum class DsdTransport : uint8_t {
    /**
     * No DSD capability detected on this alternate setting.
     * The alt setting may carry PCM audio only, or the AS_GENERAL descriptor
     * was absent or unparseable.
     */
    None         = 0,

    /**
     * DoP (DSD over PCM) is theoretically possible via software framing.
     *
     * The alt setting uses bFormatType == TYPE_I with bSubslotSize ≥ 3 bytes
     * (24-bit or 32-bit container), which is the minimum requirement for
     * encoding a DoP marker + DSD data byte pair within one PCM sample word.
     *
     * The DAC itself is not required to advertise DoP support — a suitable
     * bSubslotSize is the only prerequisite for the driver to attempt DoP.
     * Whether the DAC actually recognises and handles DoP frames is a
     * firmware-level capability outside the USB descriptor scope.
     */
    DopOnly      = 1,

    /**
     * Native DSD via bmFormats RAW_DATA bit in a TYPE_I alternate setting.
     *
     * AS_GENERAL has bFormatType == 0x01 (TYPE_I) and bmFormats bit D31 set
     * (UAC_FORMAT_TYPE_I_RAW_DATA = 0x80000000), or the non-conformant
     * heuristic value 0x1F is observed.
     *
     * Less common than NativeTypeIV; seen on some iFi and older FiiO products.
     * The raw DSD data is packed into TYPE_I word containers in this mode.
     */
    NativeTypeIRaw = 2,

    /**
     * Native DSD via FORMAT_TYPE_IV (Raw Data) — the definitive indicator.
     *
     * AS_GENERAL has bFormatType == 0x04 (FORMAT_TYPE_IV) and/or a matching
     * FORMAT_TYPE_IV descriptor was found in the interface's extra[] blob.
     * bmFormats bit D0 (UAC_FORMAT_TYPE_IV_RAW_DATA = 0x00000001) confirms
     * the raw data format is DSD rather than IEC 61937 compressed audio.
     *
     * This is the standard and most widely adopted path for Native DSD on
     * modern DACs: FiiO, RME, Topping, Matrix Audio, Gustard, etc.
     */
    NativeTypeIV = 3,
};

// ─────────────────────────────────────────────────────────────────────────────
// Per-endpoint DSD annotation
// ─────────────────────────────────────────────────────────────────────────────

/**
 * DSD transport classification for one `Uac2StreamingEndpointInfo` entry.
 *
 * Produced by `uac2_classify_endpoint_dsd()` for a single alternate setting.
 *
 * @property transport        The DSD transport mode derived from descriptors.
 * @property is_native_dsd    Convenience: true when transport >= NativeTypeIRaw.
 * @property is_dop_capable   Convenience: true when transport >= DopOnly.
 */
struct Uac2EndpointDsdInfo {
    DsdTransport transport     = DsdTransport::None;
    bool         is_native_dsd = false;
    bool         is_dop_capable = false;
};

// ─────────────────────────────────────────────────────────────────────────────
// Device-level DSD capability summary
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Device-level DSD capability summary produced by `uac2_detect_native_dsd()`.
 *
 * Aggregates the findings across all alternate settings into a single struct
 * that the JNI layer and Kotlin side can use to decide the playback path:
 *   - If `supports_native_dsd` → use `native_dsd_alt_setting` and send raw DSD
 *   - Else if `supports_dop`   → use `pcm_alt_setting` and encode DSD as DoP
 *   - Else                     → PCM only; DSD playback is not possible
 *
 * @property supports_native_dsd    true when at least one alt setting reports
 *                                  NativeTypeIRaw or NativeTypeIV transport.
 * @property supports_dop           true when at least one TYPE_I alt setting
 *                                  has bSubslotSize ≥ 3 (fits DoP framing).
 * @property native_dsd_transport   The strongest DSD transport mode found.
 * @property native_dsd_interface   bInterfaceNumber of the best native DSD
 *                                  alt setting (valid when supports_native_dsd).
 * @property native_dsd_alt_setting bAlternateSetting of the best native DSD
 *                                  configuration (valid when supports_native_dsd).
 * @property native_dsd_endpoint    bEndpointAddress of the native DSD endpoint
 *                                  (valid when supports_native_dsd).
 * @property native_dsd_bandwidth   effective_bytes_per_uframe of the native DSD
 *                                  alt setting (for telemetry / logging).
 * @property pcm_interface          bInterfaceNumber of a DoP-capable PCM alt
 *                                  setting (valid when supports_dop).
 * @property pcm_alt_setting        bAlternateSetting of a DoP-capable PCM alt
 *                                  setting (valid when supports_dop).
 * @property pcm_endpoint           bEndpointAddress of the DoP-capable PCM alt.
 * @property pcm_bandwidth          effective_bytes_per_uframe of the PCM alt
 *                                  setting selected for DoP.
 */
struct Uac2DsdCapabilitySummary {
    bool         supports_native_dsd    = false;
    bool         supports_dop           = false;
    DsdTransport native_dsd_transport   = DsdTransport::None;

    // ── Native DSD alt setting fields (populated when supports_native_dsd) ───
    uint8_t      native_dsd_interface   = 0;
    uint8_t      native_dsd_alt_setting = 0;
    uint8_t      native_dsd_endpoint    = 0;
    uint16_t     native_dsd_bandwidth   = 0;   ///< effective_bytes_per_uframe

    // ── PCM / DoP alt setting fields (populated when supports_dop) ───────────
    uint8_t      pcm_interface          = 0;
    uint8_t      pcm_alt_setting        = 0;
    uint8_t      pcm_endpoint           = 0;
    uint16_t     pcm_bandwidth          = 0;   ///< effective_bytes_per_uframe
};

// ─────────────────────────────────────────────────────────────────────────────
// Public API
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Classify the DSD transport capability of a single alternate setting.
 *
 * Inspects the parsed `Uac2AsGeneralInfo` data already collected by
 * `uac2_find_streaming_endpoints()` — no additional USB communication occurs.
 *
 * ### Classification rules (in descending priority order)
 *
 *  1. **NativeTypeIV** — if any of:
 *       - `as_general.has_format_type_iv == true` (FORMAT_TYPE_IV descriptor found)
 *       - `as_general.format_type == 0x04`        (AS_GENERAL bFormatType == TYPE_IV)
 *
 *  2. **NativeTypeIRaw** — if all of:
 *       - `as_general.format_type == 0x01`         (AS_GENERAL bFormatType == TYPE_I)
 *       - `as_general.bm_formats & 0x80000000`     (RAW_DATA bit per ADF2 §2.3.1)
 *         OR `as_general.bm_formats == 0x0000001F` (non-conformant heuristic)
 *
 *  3. **DopOnly** — if all of:
 *       - `as_general.format_type == 0x01`         (TYPE_I PCM stream)
 *       - `as_general.subslot_size >= 3`           (24-bit or wider; fits DoP frame)
 *       - `as_general.bm_formats & 0x00000001`     (PCM bit set; valid PCM alt setting)
 *
 *  4. **None** — all other cases.
 *
 * @param as_general  Parsed AS_GENERAL descriptor data.  If `is_present` is
 *                    false the function returns `DsdTransport::None`.
 * @return            Transport classification for this alternate setting.
 */
Uac2EndpointDsdInfo uac2_classify_endpoint_dsd(
        const Uac2AsGeneralInfo &as_general) noexcept;

/**
 * Scan the pre-parsed endpoint list and produce a device-level DSD summary.
 *
 * Iterates every entry in `candidates` (produced by
 * `uac2_find_streaming_endpoints()`), classifies each alternate setting, then
 * selects the best native DSD alt setting and the best PCM (DoP-capable) alt
 * setting according to the priority and bandwidth rules described in the module
 * header.
 *
 * No USB control transfers are issued; the analysis is purely over the
 * descriptor data already parsed in Step 2.
 *
 * Detailed per-endpoint and final-summary Logcat messages are emitted under
 * the tag `"Uac2DsdDetect"` so that DSD detection output is clearly separated
 * from the basic endpoint scanner output (`"Uac2DescParser"`).
 *
 * @param candidates  Non-empty vector from `uac2_find_streaming_endpoints()`.
 *                    Passing an empty vector produces a default-constructed
 *                    summary (all fields zero / false) and a WARN log.
 * @return            Summary of the device's DSD capability.
 *
 * @see uac2_find_streaming_endpoints
 * @see uac2_classify_endpoint_dsd
 * @see Uac2DsdCapabilitySummary
 */
Uac2DsdCapabilitySummary uac2_detect_native_dsd(
        const std::vector<Uac2StreamingEndpointInfo> &candidates);

/**
 * Return a short human-readable label for a `DsdTransport` value.
 *
 * Intended for Logcat messages and diagnostic strings.
 *
 * @param t  Transport classification to describe.
 * @return   Static string label (never null).
 */
const char *dsd_transport_name(DsdTransport t) noexcept;
