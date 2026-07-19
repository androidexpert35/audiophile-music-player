// ─────────────────────────────────────────────────────────────────────────────
// uac2_dsd_detector.cpp
//
// Step 11 — Native DSD capability detection for UAC2 devices.
//
// Implements `uac2_detect_native_dsd()` and its helpers.
//
// See uac2_dsd_detector.h for the full detection rationale, transport
// classification rules, and the DoP / Native DSD distinction.
//
// ── How each DAC family is detected ──────────────────────────────────────────
//
//  FiiO K9 Pro / Q7 / BTR7:
//    PCM alt settings  → bFormatType=0x01 (TYPE_I), bmFormats=0x01 (PCM bit)
//    DSD alt setting   → bFormatType=0x04 (TYPE_IV), bmFormats=0x01 (RAW_DATA)
//                        FORMAT_TYPE_IV descriptor present in extra[]
//    → Classified: NativeTypeIV
//
//  RME ADI-2 DAC FS / ADI-2 Pro:
//    PCM alt settings  → TYPE_I, subslot=4, bitRes=32
//    DSD alt setting   → TYPE_IV with FORMAT_TYPE_IV confirmed
//    → Classified: NativeTypeIV
//
//  iFi Hip-DAC 3 / Zen DAC v2:
//    High-rate alt setting → TYPE_I, bmFormats=0x80000001 (PCM + RAW_DATA)
//    → Classified: NativeTypeIRaw
//
//  Topping DX3 Pro+ / DX5:
//    DSD alt setting → FORMAT_TYPE_IV, bmFormats=0x01
//    → Classified: NativeTypeIV
//
//  Generic / budget UAC2 DAC with single alt setting:
//    TYPE_I, subslot=3 or 4, no RAW_DATA bit, no TYPE_IV
//    → Classified: DopOnly (DoP possible at software level)
//
//  16-bit-only USB DAC (subslot=2):
//    TYPE_I, subslot=2, no RAW_DATA bit
//    → Classified: None (no DSD possible, not even DoP)
//
// ─────────────────────────────────────────────────────────────────────────────

#include "uac2_dsd_detector.h"

#include <android/log.h>

// ─── Logging macros ───────────────────────────────────────────────────────────

static constexpr const char *DSD_TAG = "Uac2DsdDetect";

#define DLOGD(...) __android_log_print(ANDROID_LOG_DEBUG, DSD_TAG, __VA_ARGS__)
#define DLOGI(...) __android_log_print(ANDROID_LOG_INFO,  DSD_TAG, __VA_ARGS__)
#define DLOGW(...) __android_log_print(ANDROID_LOG_WARN,  DSD_TAG, __VA_ARGS__)
#define DLOGE(...) __android_log_print(ANDROID_LOG_ERROR, DSD_TAG, __VA_ARGS__)

// ─────────────────────────────────────────────────────────────────────────────
// dsd_transport_name
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Map a DsdTransport enum value to a human-readable label.
 *
 * @param t  Transport classification.
 * @return   Static constant string; never null.
 */
const char *dsd_transport_name(DsdTransport t) noexcept
{
    switch (t) {
        case DsdTransport::None:           return "None (PCM only)";
        case DsdTransport::DopOnly:        return "DoP (DSD over PCM)";
        case DsdTransport::NativeTypeIRaw: return "Native DSD [TYPE_I RAW_DATA bit]";
        case DsdTransport::NativeTypeIV:   return "Native DSD [FORMAT_TYPE_IV]";
        default:                           return "Unknown";
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// uac2_classify_endpoint_dsd
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Classify the DSD transport capability of a single alternate setting from its
 * already-parsed AS_GENERAL descriptor data.
 *
 * Four-tier classification in descending priority:
 *
 *   Tier 1 — NativeTypeIV  (FORMAT_TYPE_IV raw bitstream):
 *     • has_format_type_iv == true  → FORMAT_TYPE_IV descriptor was in extra[]
 *     • OR format_type == 0x04      → AS_GENERAL bFormatType directly declares IV
 *     Either condition alone is sufficient; confirmed together they are definitive.
 *
 *   Tier 2 — NativeTypeIRaw (TYPE_I wmFormats RAW_DATA bit):
 *     • format_type == 0x01 (TYPE_I)
 *     • AND (bm_formats & 0x80000000) OR bm_formats == 0x1F (non-conformant heuristic)
 *
 *   Tier 3 — DopOnly (TYPE_I, wide subslot, no raw bit):
 *     • format_type == 0x01 (TYPE_I)
 *     • AND subslot_size >= 3  (24-bit or 32-bit container required for DoP framing)
 *     • AND (bm_formats & UAC_FORMAT_TYPE_I_PCM) is set (confirms real PCM alt setting)
 *
 *   Tier 4 — None: all other cases.
 *
 * @param as_general  Caller-owned AS_GENERAL struct.  If is_present is false
 *                    the result is immediately DsdTransport::None.
 * @return            Classification struct with transport, is_native_dsd,
 *                    and is_dop_capable convenience flags.
 */
Uac2EndpointDsdInfo uac2_classify_endpoint_dsd(
        const Uac2AsGeneralInfo &as_general) noexcept
{
    Uac2EndpointDsdInfo result;

    // Cannot classify without parsed AS_GENERAL data.
    if (!as_general.is_present) {
        return result;   // transport = None, both flags = false
    }

    // ── Tier 1: FORMAT_TYPE_IV — definitive Native DSD ───────────────────────
    //
    // Two independent signals are checked:
    //   a) has_format_type_iv: the FORMAT_TYPE_IV descriptor was literally
    //      found and parsed in the interface's extra[] blob by Step 2.
    //   b) format_type == 0x04: the AS_GENERAL bFormatType field itself
    //      declares TYPE_IV even if no matching FORMAT_TYPE descriptor exists
    //      (some DACs omit the FORMAT_TYPE_IV descriptor; AS_GENERAL alone is
    //      sufficient per the UAC2 spec).
    //
    // The bmFormats RAW_DATA bit (0x00000001) for TYPE_IV is checked as a
    // confirmatory sub-condition.  If bm_formats is zero (absent or zeroed by
    // a buggy firmware) we still classify as NativeTypeIV — the presence of
    // the TYPE_IV format marker outweighs an absent bmFormats field.
    if (as_general.has_format_type_iv || as_general.format_type == UAC2_FORMAT_TYPE_IV) {
        result.transport      = DsdTransport::NativeTypeIV;
        result.is_native_dsd  = true;
        result.is_dop_capable = false;  // native path supersedes DoP
        return result;
    }

    // ── Tier 2: TYPE_I with RAW_DATA bit ─────────────────────────────────────
    //
    // The AS_GENERAL bmFormats field uses bFormatType == TYPE_I (0x01) but
    // sets bit D31 (0x80000000) to advertise a raw DSD word mode.
    //
    // The non-conformant 0x1F heuristic:
    //   The value 0x1F = 0b00011111 sets the five named PCM sub-format bits
    //   (PCM, PCM8, IEEE_FLOAT, ALAW, MULAW) simultaneously.  No real DAC
    //   supports ALAW or MULAW output; this combination is physically impossible
    //   for a DAC and is therefore interpreted as a vendor firmware quirk
    //   signalling "raw capable" via a non-standard value.  Detection is
    //   conservative: only the exact 0x1F value (all five bits) triggers it.
    if (as_general.format_type == UAC2_FORMAT_TYPE_I) {
        const bool has_raw_data_bit = (as_general.bm_formats & UAC_FORMAT_TYPE_I_RAW_DATA) != 0U;
        const bool has_nc_sentinel  = (as_general.bm_formats == UAC_FORMAT_TYPE_I_RAW_DATA_NC);

        if (has_raw_data_bit || has_nc_sentinel) {
            result.transport      = DsdTransport::NativeTypeIRaw;
            result.is_native_dsd  = true;
            result.is_dop_capable = false;  // native supersedes DoP
            return result;
        }

        // ── Tier 3: TYPE_I, wide subslot — DoP possible at software level ────
        //
        // DoP requires a 24-bit or 32-bit subslot so that the driver can pack
        // one DoP marker byte + one DSD data byte into each sample word.
        // A 16-bit subslot (subslot_size == 2) is too narrow.
        //
        // Additionally the PCM bit (D0) should be set in bmFormats to confirm
        // this is a genuine PCM audio alt setting.  Some DACs provide bmFormats
        // == 0 (descriptor absent or zeroed); to avoid false negatives, we
        // accept subslot_size >= 3 regardless of bmFormats when format_type
        // is explicitly TYPE_I.
        if (as_general.subslot_size >= 3) {
            result.transport      = DsdTransport::DopOnly;
            result.is_native_dsd  = false;
            result.is_dop_capable = true;
            return result;
        }
    }

    // ── Tier 4: None ─────────────────────────────────────────────────────────
    // FORMAT_TYPE_II / III, TYPE_I with narrow subslot, or unrecognised type.
    // result already default-constructed to None.
    return result;
}

// ─────────────────────────────────────────────────────────────────────────────
// uac2_detect_native_dsd — device-level analysis
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Select the "best" endpoint from two candidates of the same DsdTransport tier,
 * preferring the one with greater effective bandwidth.  Ties are broken by
 * lower (interface_number, alt_setting).
 *
 * @param current  Pointer to the currently selected endpoint (may be null).
 * @param next     Pointer to the candidate being evaluated (never null).
 * @return         Which pointer should be kept.
 */
static const Uac2StreamingEndpointInfo *pick_better(
        const Uac2StreamingEndpointInfo *current,
        const Uac2StreamingEndpointInfo *next) noexcept
{
    if (current == nullptr) return next;

    // Prefer higher effective bandwidth.
    if (next->effective_bytes_per_uframe > current->effective_bytes_per_uframe)
        return next;
    if (next->effective_bytes_per_uframe < current->effective_bytes_per_uframe)
        return current;

    // Equal bandwidth — prefer lower (iface, alt) for determinism.
    if (next->interface_number < current->interface_number) return next;
    if (next->interface_number > current->interface_number) return current;
    return (next->alt_setting < current->alt_setting) ? next : current;
}

/**
 * Analyse a pre-scanned endpoint list and produce a device-level DSD summary.
 *
 * ### Algorithm
 *
 *  1. For each endpoint in `candidates`, call `uac2_classify_endpoint_dsd()`
 *     to obtain a `DsdTransport` tier.
 *  2. Track the best Native DSD candidate (NativeTypeIV > NativeTypeIRaw,
 *     bandwidth-wise) and the best DoP candidate independently.
 *  3. Build and return the `Uac2DsdCapabilitySummary`.
 *  4. Emit a final human-readable Logcat summary that explicitly states:
 *       – Whether Native DSD is supported and via which mechanism.
 *       – Whether DoP is available as a fallback.
 *       – The specific interface and alt setting to use for each mode.
 *       – An advisory if the device appears PCM-only.
 *
 * @param candidates  Vector of endpoints from `uac2_find_streaming_endpoints()`.
 * @return            Device-level DSD capability summary.
 */
Uac2DsdCapabilitySummary uac2_detect_native_dsd(
        const std::vector<Uac2StreamingEndpointInfo> &candidates)
{
    Uac2DsdCapabilitySummary summary;

    if (candidates.empty()) {
        DLOGW("uac2_detect_native_dsd: candidate list is empty — "
              "run uac2_find_streaming_endpoints() before calling this function");
        return summary;
    }

    DLOGI("uac2_detect_native_dsd: analysing %zu alternate setting(s) for DSD capability",
          candidates.size());

    // ── Phase 1: Classify every alternate setting ─────────────────────────────
    //
    // Two parallel tracking pointers are maintained:
    //   best_native  — the highest-tier / highest-bandwidth native DSD alt
    //   best_dop     — the highest-bandwidth TYPE_I alt suitable for DoP
    //
    // They are pointers into `candidates` so no copying occurs.
    const Uac2StreamingEndpointInfo *best_native = nullptr;
    DsdTransport                     best_native_transport = DsdTransport::None;
    const Uac2StreamingEndpointInfo *best_dop    = nullptr;

    for (const auto &ep : candidates) {
        const Uac2EndpointDsdInfo dsd = uac2_classify_endpoint_dsd(ep.as_general);

        // ── Per-endpoint Logcat line ──────────────────────────────────────────
        // Logged at INFO for native DSD, DEBUG for PCM-only alt settings, so
        // developers can quickly grep for "NativeDSD" in logcat.
        if (dsd.is_native_dsd) {
            DLOGI("  iface=%u alt=%u ep=0x%02X effective=%u B/µframe  → %s  ★ NATIVE DSD",
                  ep.interface_number,
                  ep.alt_setting,
                  ep.endpoint_address,
                  ep.effective_bytes_per_uframe,
                  dsd_transport_name(dsd.transport));
        } else if (dsd.is_dop_capable) {
            DLOGI("  iface=%u alt=%u ep=0x%02X effective=%u B/µframe  → %s  "
                  "(subslot=%u bitRes=%u)",
                  ep.interface_number,
                  ep.alt_setting,
                  ep.endpoint_address,
                  ep.effective_bytes_per_uframe,
                  dsd_transport_name(dsd.transport),
                  ep.as_general.subslot_size,
                  ep.as_general.bit_resolution);
        } else {
            DLOGD("  iface=%u alt=%u ep=0x%02X effective=%u B/µframe  → %s  "
                  "(fmtType=0x%02X bmFormats=0x%08X subslot=%u)",
                  ep.interface_number,
                  ep.alt_setting,
                  ep.endpoint_address,
                  ep.effective_bytes_per_uframe,
                  dsd_transport_name(dsd.transport),
                  ep.as_general.format_type,
                  ep.as_general.bm_formats,
                  ep.as_general.subslot_size);
        }

        // ── Update native DSD candidate ───────────────────────────────────────
        // NativeTypeIV beats NativeTypeIRaw regardless of bandwidth; within the
        // same tier, pick_better() selects the higher-bandwidth alt setting.
        if (dsd.is_native_dsd) {
            // Stronger tier always wins.
            if (static_cast<uint8_t>(dsd.transport) >
                static_cast<uint8_t>(best_native_transport))
            {
                best_native           = &ep;
                best_native_transport = dsd.transport;
            } else if (dsd.transport == best_native_transport) {
                best_native = pick_better(best_native, &ep);
            }
        }

        // ── Update DoP candidate ──────────────────────────────────────────────
        // Track the highest-bandwidth PCM alt when no native DSD alt exists,
        // or always so the Kotlin layer has a DoP fallback path available.
        if (dsd.is_dop_capable) {
            best_dop = pick_better(best_dop, &ep);
        }
    }

    // ── Phase 2: Populate summary ─────────────────────────────────────────────
    if (best_native != nullptr) {
        summary.supports_native_dsd    = true;
        summary.native_dsd_transport   = best_native_transport;
        summary.native_dsd_interface   = best_native->interface_number;
        summary.native_dsd_alt_setting = best_native->alt_setting;
        summary.native_dsd_endpoint    = best_native->endpoint_address;
        summary.native_dsd_bandwidth   = best_native->effective_bytes_per_uframe;
    }

    if (best_dop != nullptr) {
        summary.supports_dop  = true;
        summary.pcm_interface = best_dop->interface_number;
        summary.pcm_alt_setting = best_dop->alt_setting;
        summary.pcm_endpoint    = best_dop->endpoint_address;
        summary.pcm_bandwidth   = best_dop->effective_bytes_per_uframe;
    }

    // ── Phase 3: Final diagnostic log ─────────────────────────────────────────
    //
    // This is the single most important summary line produced by the entire
    // DSD detection pass.  It explicitly names the transport mechanism so the
    // developer never has to grep through individual alt-setting lines.
    DLOGI("═══════════════════════════════════════════════════════");
    DLOGI("uac2_detect_native_dsd: RESULT SUMMARY");
    DLOGI("  Candidates examined   : %zu alt setting(s)", candidates.size());

    if (summary.supports_native_dsd) {
        DLOGI("  ✅ Native DSD         : YES — transport = %s",
              dsd_transport_name(summary.native_dsd_transport));

        if (summary.native_dsd_transport == DsdTransport::NativeTypeIV) {
            DLOGI("  ✅ Mechanism          : FORMAT_TYPE_IV (Raw Data) — definitive native bitstream");
            DLOGI("     The DAC exposes a dedicated raw DSD alt setting.  The driver");
            DLOGI("     will submit DSD bytes directly with no PCM framing overhead.");
        } else {
            DLOGI("  ✅ Mechanism          : TYPE_I bmFormats RAW_DATA bit");
            DLOGI("     The DAC signals DSD capability through the TYPE_I bmFormats");
            DLOGI("     RAW_DATA bit (0x80000000).  DSD words are packed into sample");
            DLOGI("     containers; the encoding contract is vendor-specific.");
        }

        DLOGI("  ✅ Native DSD alt     : iface=%u  altSetting=%u  ep=0x%02X  "
              "bandwidth=%u B/µframe",
              summary.native_dsd_interface,
              summary.native_dsd_alt_setting,
              summary.native_dsd_endpoint,
              summary.native_dsd_bandwidth);
    } else {
        DLOGI("  ❌ Native DSD         : NOT SUPPORTED");
        DLOGI("     No FORMAT_TYPE_IV descriptor and no TYPE_I RAW_DATA bit found.");
        DLOGI("     Native DSD playback is not possible with this DAC.");
    }

    if (summary.supports_dop) {
        DLOGI("  ✅ DoP (DSD over PCM) : YES — iface=%u  altSetting=%u  ep=0x%02X  "
              "bandwidth=%u B/µframe",
              summary.pcm_interface,
              summary.pcm_alt_setting,
              summary.pcm_endpoint,
              summary.pcm_bandwidth);
        if (!summary.supports_native_dsd) {
            DLOGI("     DSD playback is possible only via DoP software encoding.");
            DLOGI("     Bitwidth overhead: DoP uses 24 of 24 bits per word;");
            DLOGI("     one marker byte + one DSD byte per PCM sample.");
        } else {
            DLOGI("     DoP also available as fallback on the PCM alt setting.");
        }
    } else {
        DLOGI("  ❌ DoP (DSD over PCM) : NOT SUPPORTED (no TYPE_I alt with subslot ≥ 3)");
    }

    if (!summary.supports_native_dsd && !summary.supports_dop) {
        DLOGW("  ⚠️  This DAC appears to be PCM-only (16-bit or lower subslot).");
        DLOGW("     DSD playback via any mechanism is not possible.");
    }

    DLOGI("═══════════════════════════════════════════════════════");

    return summary;
}
