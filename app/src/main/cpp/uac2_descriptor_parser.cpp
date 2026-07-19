// ─────────────────────────────────────────────────────────────────────────────
// uac2_descriptor_parser.cpp
//
// USB Audio Class 2.0 descriptor scanner — implementation.
//
// Walks libusb_config_descriptor → libusb_interface → libusb_interface_descriptor
// → libusb_endpoint_descriptor to locate every valid UAC2 AudioStreaming
// isochronous OUT alternate setting.  Class-specific AS_GENERAL and
// FORMAT_TYPE_I descriptors are extracted from the interface's `extra[]` blob.
//
// References:
//   [UAC2]   USB Device Class Definition for Audio Devices, Release 2.0
//   [ADF2]   USB Audio Class 2.0 — Audio Data Formats, Release 2.0
//   [USB20]  Universal Serial Bus Specification, Revision 2.0, §9.6
//
// Endianness:
//   libusb converts all descriptor integers to host-endian before returning
//   them in the descriptor structs (confirmed in libusb.h struct comments).
//   The `extra[]` byte blob is NOT converted — it is raw USB little-endian
//   wire data.  Android ARM64 is also little-endian so a two-byte or four-byte
//   load from the blob produces the correct value *on this target*, but we
//   still use explicit byte-safe load helpers so the code is visually auditable
//   and would be correct on a hypothetical big-endian host.
// ─────────────────────────────────────────────────────────────────────────────

#include "uac2_descriptor_parser.h"

#include <android/log.h>
#include <algorithm>      // std::stable_sort
#include <cstring>        // std::memcpy

#include "libusb/libusb.h"

// ─── Logging macros ───────────────────────────────────────────────────────────

static constexpr const char *PARSER_TAG = "Uac2DescParser";

#define PLOGD(...) __android_log_print(ANDROID_LOG_DEBUG, PARSER_TAG, __VA_ARGS__)
#define PLOGI(...) __android_log_print(ANDROID_LOG_INFO,  PARSER_TAG, __VA_ARGS__)
#define PLOGW(...) __android_log_print(ANDROID_LOG_WARN,  PARSER_TAG, __VA_ARGS__)
#define PLOGE(...) __android_log_print(ANDROID_LOG_ERROR, PARSER_TAG, __VA_ARGS__)

// ─── Byte-safe little-endian readers ─────────────────────────────────────────
// These read multi-byte values from a USB raw byte buffer (class-specific
// extra[] descriptor data) without casting through a pointer, avoiding
// undefined behaviour from misaligned access even though on ARM64 / x86_64
// unaligned reads are handled in hardware.

/**
 * Read a 32-bit little-endian value from a raw byte buffer at [offset].
 *
 * @param buf    Pointer to the raw byte buffer.
 * @param offset Byte offset of the first (LSB) byte.
 * @return       32-bit value in host-endian order.
 */
static inline uint32_t read_le32(const uint8_t *buf, int offset) noexcept {
    return static_cast<uint32_t>(buf[offset])
         | (static_cast<uint32_t>(buf[offset + 1]) << 8)
         | (static_cast<uint32_t>(buf[offset + 2]) << 16)
         | (static_cast<uint32_t>(buf[offset + 3]) << 24);
}

// ─── wMaxPacketSize helpers ───────────────────────────────────────────────────

/**
 * Compute the effective bytes per USB (micro)frame from wMaxPacketSize.
 *
 * For USB 2.0 High-Speed isochronous endpoints, bits 12:11 of wMaxPacketSize
 * encode additional transactions per microframe (0 = 1×, 1 = 2×, 2 = 3×).
 * Multiplying the base packet size by (multiplier + 1) yields the actual
 * bytes transferred in each 125 µs microframe.
 *
 * This is the critical figure for determining whether the endpoint can sustain
 * a required sample rate / bit depth without under-runs:
 *   e.g. PCM 192 kHz / 32-bit / 2ch → 192000 × 4 × 2 / 8000 microframes/sec
 *                                    = 192 bytes/microframe minimum.
 *
 * @param raw  wMaxPacketSize as returned by libusb (host-endian).
 * @return     Effective bytes deliverable per microframe.
 */
static uint16_t compute_effective_bytes_per_uframe(uint16_t raw) noexcept {
    const uint16_t base_size   = raw & USB_MPS_PACKET_SIZE_MASK;
    const uint16_t multiplier  = (raw & USB_MPS_MULT_MASK) >> USB_MPS_MULT_SHIFT;
    // multiplier 0b11 is reserved; clamp to 2 (3×) for safety.
    const uint16_t safe_mult   = (multiplier > 2U) ? 2U : multiplier;
    return base_size * (safe_mult + 1U);
}

// ─── Isochronous sync-type name (for logging) ─────────────────────────────────

/**
 * Return a human-readable label for the isochronous sync type extracted
 * from bits 3:2 of bmAttributes.
 *
 * @param sync_nibble  (bmAttributes >> 2) & 0x03.
 * @return             Static string label.
 */
static const char *iso_sync_type_name(uint8_t sync_nibble) noexcept {
    switch (sync_nibble) {
        case 0x00: return "None";
        case 0x01: return "Async";
        case 0x02: return "Adaptive";
        case 0x03: return "Sync";
        default:   return "Unknown";
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// UAC2 class-specific descriptor parser
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Walk the class-specific extra[] data on a libusb_interface_descriptor and
 * attempt to parse the UAC2 AS_GENERAL [UAC2 §4.9.2] and FORMAT_TYPE_I
 * [ADF2 §2.3.1.6] sub-descriptors.
 *
 * The extra[] blob may contain multiple class-specific descriptors back-to-
 * back.  Each starts with a bLength byte followed by bDescriptorType.  This
 * function walks the blob safely, checking array bounds at every step.
 *
 * ### Why extra[] and not a separate libusb call?
 *
 * libusb does not parse class-specific (0x24) interface descriptors into
 * structured types.  The raw bytes are preserved verbatim in
 * libusb_interface_descriptor::extra.  Direct byte parsing of the blob is the
 * correct and documented approach for retrieving UAC2 descriptor fields.
 *
 * @param extra        Pointer to the raw extra descriptor blob.
 * @param extra_length Total length of the blob in bytes.
 * @param iface_num    Interface number (for log context only).
 * @param alt_num      Alternate setting number (for log context only).
 * @return             Populated Uac2AsGeneralInfo; is_present == false on failure.
 */
static Uac2AsGeneralInfo parse_as_class_specific(
        const unsigned char *extra,
        int                  extra_length,
        uint8_t              iface_num,
        uint8_t              alt_num) noexcept
{
    Uac2AsGeneralInfo info;

    if (extra == nullptr || extra_length < 2) {
        PLOGD("  iface=%u alt=%u: no class-specific extra data (extra_length=%d)",
              iface_num, alt_num, extra_length);
        return info;  // is_present remains false
    }

    // Raw pointer cast is safe: we treat the buffer as a read-only byte array
    // and never dereference beyond [extra_length - 1].
    const auto *buf = reinterpret_cast<const uint8_t *>(extra);

    // Walk each sub-descriptor.  We stop when fewer than 2 bytes remain
    // (not enough to read bLength + bDescriptorType).
    int offset = 0;
    while (offset + 1 < extra_length) {
        const uint8_t desc_len  = buf[offset];
        const uint8_t desc_type = buf[offset + 1];

        // A descriptor must declare at least 3 bytes (len + type + subtype).
        // A zero-length entry would cause an infinite loop — skip it.
        if (desc_len < 3) {
            PLOGW("  iface=%u alt=%u: malformed CS descriptor at offset=%d bLength=%u — stopping walk",
                  iface_num, alt_num, offset, desc_len);
            break;
        }

        // Guard against a bLength that extends past the buffer.
        if (offset + static_cast<int>(desc_len) > extra_length) {
            PLOGW("  iface=%u alt=%u: CS descriptor at offset=%d bLength=%u overruns buffer (extra_length=%d)",
                  iface_num, alt_num, offset, desc_len, extra_length);
            break;
        }

        // Only care about Class-Specific Interface descriptors (0x24).
        if (desc_type != UAC_CS_INTERFACE) {
            PLOGD("  iface=%u alt=%u: skipping non-CS descriptor type=0x%02X at offset=%d",
                  iface_num, alt_num, desc_type, offset);
            offset += static_cast<int>(desc_len);
            continue;
        }

        const uint8_t subtype = buf[offset + 2];

        // ── AS_GENERAL (bDescriptorSubtype = 0x01) ───────────────────────────
        // UAC2 spec §4.9.2, Table 4-27.
        // Minimum length verified against UAC2 spec: bLength must be ≥ 16.
        if (subtype == UAC2_AS_DESC_SUBTYPE_GENERAL) {
            if (desc_len < UAC2_AS_GENERAL_MIN_LEN) {
                PLOGW("  iface=%u alt=%u: AS_GENERAL bLength=%u < minimum %u — skipping",
                      iface_num, alt_num, desc_len, UAC2_AS_GENERAL_MIN_LEN);
                offset += static_cast<int>(desc_len);
                continue;
            }

            // [UAC2 Table 4-27] — total bLength = 16.
            // Offset  Size  Field
            //  0       1    bLength
            //  1       1    bDescriptorType    (0x24)
            //  2       1    bDescriptorSubtype (0x01)
            //  3       1    bTerminalLink
            //  4       1    bmControls              ← 1 byte, NOT 4 (UAC2 §4.9.2)
            //  5       1    bFormatType
            //  6       4    bmFormats          [LE]
            // 10       1    bNrChannels
            // 11       4    bmChannelConfig    [LE]
            // 15       1    iChannelNames
            //
            // bmControls is a single byte in UAC2 (per-stream Active/Valid
            // Alternate Setting controls). Reading it as 4 bytes shifted every
            // subsequent field by +3, so bFormatType / bmFormats / bNrChannels
            // were parsed from garbage bytes (and bmChannelConfig over-read into
            // the following descriptor). That made the strict-PCM endpoint filter
            // reject fully valid Type-I PCM alt settings — e.g. the enhanced
            // libusb sink could not open on a FiiO KA5 whose 32-bit alt setting
            // advertised bmFormats=0x00000001 but was misread as 0x00030200.
            info.terminal_link     = buf[offset + 3];
            info.format_type       = buf[offset + 5];
            info.bm_formats        = read_le32(buf, offset + 6);
            info.nr_channels       = buf[offset + 10];
            info.bm_channel_config = read_le32(buf, offset + 11);

            PLOGD("  iface=%u alt=%u: AS_GENERAL — termLink=%u fmtType=0x%02X "
                  "bmFormats=0x%08X nrCh=%u bmChCfg=0x%08X",
                  iface_num, alt_num,
                  info.terminal_link, info.format_type,
                  info.bm_formats, info.nr_channels, info.bm_channel_config);

            info.is_present = true;
        }

        // ── FORMAT_TYPE (bDescriptorSubtype = 0x02) ──────────────────────────
        // Dispatch on bFormatType at offset [3] to handle both TYPE_I (PCM)
        // and TYPE_IV (Raw Data / Native DSD).
        else if (subtype == UAC2_AS_DESC_SUBTYPE_FMT_TYPE) {
            if (desc_len < 4) {
                // Every FORMAT_TYPE descriptor needs at least bLength + bType +
                // bSubtype + bFormatType — bail if the descriptor is too short.
                PLOGW("  iface=%u alt=%u: FORMAT_TYPE bLength=%u < 4 — skipping",
                      iface_num, alt_num, desc_len);
                offset += static_cast<int>(desc_len);
                continue;
            }

            const uint8_t format_type_id = buf[offset + 3];

            // ── FORMAT_TYPE_I (PCM samples) ───────────────────────────────────
            // [ADF2 Table 2-2]:
            //   [0] bLength (must be 6)  [3] bFormatType = 0x01
            //   [4] bSubslotSize         [5] bBitResolution
            if (format_type_id == UAC2_FORMAT_TYPE_I) {
                if (desc_len < UAC2_FORMAT_TYPE_I_LEN) {
                    PLOGW("  iface=%u alt=%u: FORMAT_TYPE_I bLength=%u < %u — skipping",
                          iface_num, alt_num, desc_len, UAC2_FORMAT_TYPE_I_LEN);
                    offset += static_cast<int>(desc_len);
                    continue;
                }

                info.subslot_size   = buf[offset + 4];
                info.bit_resolution = buf[offset + 5];

                PLOGD("  iface=%u alt=%u: FORMAT_TYPE_I — "
                      "subslotSize=%u bitResolution=%u",
                      iface_num, alt_num,
                      info.subslot_size, info.bit_resolution);
            }

            // ── FORMAT_TYPE_IV (Raw Data — Native DSD / IEC 61937) ────────────
            // [ADF2 §2.3.4]: 4-byte fixed layout; no extra payload fields.
            // The presence of this descriptor is the definitive confirmation
            // that the alternate setting carries a raw bitstream.  The
            // AS_GENERAL bmFormats field (parsed above) specifies which exact
            // raw format is supported (RAW_DATA = bit 0 for DSD).
            else if (format_type_id == UAC2_FORMAT_TYPE_IV) {
                if (desc_len < UAC2_FORMAT_TYPE_IV_LEN) {
                    PLOGW("  iface=%u alt=%u: FORMAT_TYPE_IV bLength=%u < %u — skipping",
                          iface_num, alt_num, desc_len, UAC2_FORMAT_TYPE_IV_LEN);
                    offset += static_cast<int>(desc_len);
                    continue;
                }

                // Record the confirmed presence of a TYPE_IV descriptor so the
                // DSD detector can use it as a definitive native DSD signal
                // independent of the AS_GENERAL bFormatType field.
                info.has_format_type_iv = true;

                PLOGI("  iface=%u alt=%u: FORMAT_TYPE_IV (Raw Data) — "
                      "native DSD / IEC 61937 confirmed by Format Type descriptor",
                      iface_num, alt_num);
            }
            else {
                // FORMAT_TYPE_II (compressed), FORMAT_TYPE_III (IEC 60958 framing),
                // or an extended / vendor format.  Logged for visibility; the Driver
                // does not currently handle these.
                PLOGD("  iface=%u alt=%u: FORMAT_TYPE bFormatType=0x%02X "
                      "(not TYPE_I or TYPE_IV) — recorded, not parsed",
                      iface_num, alt_num, format_type_id);
            }
        }
        else {
            PLOGD("  iface=%u alt=%u: unhandled CS subtype=0x%02X len=%u at offset=%d",
                  iface_num, alt_num, subtype, desc_len, offset);
        }

        offset += static_cast<int>(desc_len);
    }

    return info;
}

// ─────────────────────────────────────────────────────────────────────────────
// Endpoint filter
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Examine one endpoint descriptor against all UAC2 isochronous OUT criteria.
 *
 * @param ep       Endpoint descriptor to evaluate.
 * @param iface    Interface number (log context).
 * @param alt      AltSetting number (log context).
 * @param ep_idx   Endpoint index within the interface (log context).
 * @return         true if the endpoint passes ALL six filters.
 */
static bool is_uac2_iso_out_endpoint(const libusb_endpoint_descriptor &ep,
                                     uint8_t iface, uint8_t alt, uint8_t ep_idx) noexcept
{
    // ─ Filter ④: transfer type must be Isochronous ──────────────────────────
    const uint8_t xfer_type = ep.bmAttributes & LIBUSB_TRANSFER_TYPE_MASK;
    if (xfer_type != LIBUSB_ENDPOINT_TRANSFER_TYPE_ISOCHRONOUS) {
        PLOGD("    ep[%u] addr=0x%02X: skip — not isochronous (bmAttr=0x%02X)",
              ep_idx, ep.bEndpointAddress, ep.bmAttributes);
        return false;
    }

    // ─ Filter ⑤: direction must be OUT (bit 7 = 0) ──────────────────────────
    if ((ep.bEndpointAddress & LIBUSB_ENDPOINT_DIR_MASK) != LIBUSB_ENDPOINT_OUT) {
        PLOGD("    ep[%u] addr=0x%02X: skip — IN direction (not OUT)",
              ep_idx, ep.bEndpointAddress);
        return false;
    }

    // ─ Filter ⑥: wMaxPacketSize must be non-zero ────────────────────────────
    // Alt setting 0 is the zero-bandwidth placeholder; wMaxPacketSize == 0
    // there.  We must never submit transfers to it.
    if (ep.wMaxPacketSize == 0) {
        PLOGD("    ep[%u] addr=0x%02X alt=%u: skip — wMaxPacketSize == 0 (zero-bandwidth alt setting)",
              ep_idx, ep.bEndpointAddress, alt);
        return false;
    }

    // All filters passed.
    (void)iface;  // already captured in the outer loop's log context
    return true;
}

// ─────────────────────────────────────────────────────────────────────────────
// Main scanner
// ─────────────────────────────────────────────────────────────────────────────

std::vector<Uac2StreamingEndpointInfo>
uac2_find_streaming_endpoints(libusb_device_handle *device_handle)
{
    std::vector<Uac2StreamingEndpointInfo> results;

    if (device_handle == nullptr) {
        PLOGE("uac2_find_streaming_endpoints: null device_handle — aborting");
        return results;
    }

    // ── Step A: Get the underlying device from the handle ─────────────────────
    // libusb_get_device() never fails and never allocates; it returns the
    // device object that the handle was constructed from.  No reference-
    // counting change occurs — the device remains owned by the context.
    libusb_device *device = libusb_get_device(device_handle);

    // ── Step B: Retrieve the active configuration descriptor ─────────────────
    // The active config is the one currently selected by the device.  On
    // Android with LIBUSB_OPTION_NO_DEVICE_DISCOVERY set, the device
    // descriptor comes from the FD-wrapped device; the config descriptor is
    // fetched via control transfer internally.
    //
    // We wrap the config pointer in a local scope and always free it in the
    // cleanup block to prevent leaks on any early-return path.
    libusb_config_descriptor *config = nullptr;
    {
        const int ret = libusb_get_active_config_descriptor(device, &config);
        if (ret != LIBUSB_SUCCESS) {
            PLOGE("uac2_find_streaming_endpoints: libusb_get_active_config_descriptor failed — %s (%d)",
                  libusb_error_name(ret), ret);
            return results;
        }
    }

    PLOGI("uac2_find_streaming_endpoints: scanning config #%u (%u interface(s))",
          config->bConfigurationValue,
          config->bNumInterfaces);

    // ── Step C: Iterate interfaces and alternate settings ─────────────────────
    for (uint8_t iface_idx = 0; iface_idx < config->bNumInterfaces; ++iface_idx) {
        const libusb_interface &iface = config->interface[iface_idx];

        for (int alt_idx = 0; alt_idx < iface.num_altsetting; ++alt_idx) {
            const libusb_interface_descriptor &alt = iface.altsetting[alt_idx];

            const uint8_t iface_num = alt.bInterfaceNumber;
            const uint8_t alt_num   = alt.bAlternateSetting;

            // ─ Filter ①: Interface Class must be Audio ───────────────────────
            if (alt.bInterfaceClass != LIBUSB_CLASS_AUDIO) {
                continue;  // Silent: non-audio interfaces are expected; no log needed.
            }

            // ─ Filter ②: Interface SubClass must be AudioStreaming ────────────
            if (alt.bInterfaceSubClass != UAC_SUBCLASS_AUDIOSTREAMING) {
                PLOGD("  iface=%u alt=%u: skip — SubClass=0x%02X (not AudioStreaming 0x02)",
                      iface_num, alt_num, alt.bInterfaceSubClass);
                continue;
            }

            // ─ Filter ③: Protocol must be UAC Version 2.0 ────────────────────
            if (alt.bInterfaceProtocol != UAC_PROTOCOL_VERSION_2) {
                PLOGW("  iface=%u alt=%u: skip — Protocol=0x%02X (not UAC2 0x20; likely UAC1 or unknown)",
                      iface_num, alt_num, alt.bInterfaceProtocol);
                continue;
            }

            PLOGD("  iface=%u alt=%u: UAC2 AudioStreaming — scanning %u endpoint(s)",
                  iface_num, alt_num, alt.bNumEndpoints);

            // ── Step D: Scan endpoints on this alternate setting ───────────────
            bool found_qualifying_ep = false;
            for (uint8_t ep_idx = 0; ep_idx < alt.bNumEndpoints; ++ep_idx) {
                const libusb_endpoint_descriptor &ep = alt.endpoint[ep_idx];

                if (!is_uac2_iso_out_endpoint(ep, iface_num, alt_num, ep_idx)) {
                    continue;
                }

                // ── Step E: All six filters passed — populate result struct ────

                Uac2StreamingEndpointInfo info;
                info.interface_number = iface_num;
                info.alt_setting      = alt_num;
                info.endpoint_address = ep.bEndpointAddress;
                info.max_packet_size_raw        = ep.wMaxPacketSize;
                info.effective_bytes_per_uframe =
                    compute_effective_bytes_per_uframe(ep.wMaxPacketSize);
                info.endpoint_interval = ep.bInterval;
                info.sync_type         = (ep.bmAttributes >> 2) & 0x03U;

                // ── Step F: Parse class-specific AS_GENERAL descriptor ─────────
                // Located in the interface's extra[] blob, not in the endpoint's.
                // Parse failures are logged inside parse_as_class_specific but do
                // not disqualify the endpoint — some devices omit the CS blob.
                info.as_general = parse_as_class_specific(
                        alt.extra,
                        alt.extra_length,
                        iface_num,
                        alt_num);

                // ── Step G: Log the discovered endpoint ───────────────────────
                PLOGI("  [FOUND] iface=%u alt=%u ep=0x%02X "
                      "wMaxPkt=0x%04X (base=%u mult=%u effective=%u bytes/uframe) "
                      "interval=%u sync=%s | "
                      "AS_GENERAL present=%s fmtType=0x%02X nrCh=%u "
                      "subslot=%u bitRes=%u fmtTypeIV=%s bmFormats=0x%08X",
                      info.interface_number,
                      info.alt_setting,
                      info.endpoint_address,
                      info.max_packet_size_raw,
                      static_cast<unsigned>(info.max_packet_size_raw & USB_MPS_PACKET_SIZE_MASK),
                      static_cast<unsigned>((info.max_packet_size_raw & USB_MPS_MULT_MASK) >> USB_MPS_MULT_SHIFT),
                      info.effective_bytes_per_uframe,
                      info.endpoint_interval,
                      iso_sync_type_name(info.sync_type),
                      info.as_general.is_present ? "yes" : "no",
                      info.as_general.format_type,
                      info.as_general.nr_channels,
                      info.as_general.subslot_size,
                      info.as_general.bit_resolution,
                      info.as_general.has_format_type_iv ? "YES" : "no",
                      info.as_general.bm_formats);

                results.push_back(info);
                found_qualifying_ep = true;

                // A valid AudioStreaming interface has exactly one isochronous
                // OUT data endpoint per alternate setting.  Stop searching this
                // alt setting once we've found it to avoid duplicate entries.
                break;
            }

            // Log non-fatal absence: the AS interface exists but has no
            // qualifying endpoint (e.g., alt setting 0 zero-bandwidth placeholder).
            if (!found_qualifying_ep && alt.bNumEndpoints > 0) {
                PLOGD("  iface=%u alt=%u: UAC2 AS interface has endpoint(s) but none qualified (likely alt0 zero-BW)",
                      iface_num, alt_num);
            }
        }
    }

    // ── Step H: Release the configuration descriptor ─────────────────────────
    // Must always be called to free libusb's internal allocation.  Done here
    // so the descriptor remains valid throughout the scanning loop above.
    libusb_free_config_descriptor(config);
    config = nullptr;

    // ── Step I: Final summary log ─────────────────────────────────────────────
    if (results.empty()) {
        PLOGW("uac2_find_streaming_endpoints: no qualifying UAC2 isochronous OUT endpoint found — "
              "device may not be UAC2 compliant, or kernel UAC driver owns the interface");
    } else {
        PLOGI("uac2_find_streaming_endpoints: found %zu qualifying endpoint(s)",
              results.size());

        // Sort ascending by (interface_number, alt_setting) for deterministic ordering.
        std::stable_sort(results.begin(), results.end(),
            [](const Uac2StreamingEndpointInfo &a, const Uac2StreamingEndpointInfo &b) {
                if (a.interface_number != b.interface_number)
                    return a.interface_number < b.interface_number;
                return a.alt_setting < b.alt_setting;
            });
    }

    return results;
}

// ─────────────────────────────────────────────────────────────────────────────
// Best-endpoint selectors
// ─────────────────────────────────────────────────────────────────────────────

const Uac2StreamingEndpointInfo *
uac2_select_best_endpoint(const std::vector<Uac2StreamingEndpointInfo> &candidates)
{
    if (candidates.empty()) {
        PLOGW("uac2_select_best_endpoint: candidate list is empty — returning nullptr");
        return nullptr;
    }

    // Select the entry with the highest effective_bytes_per_uframe — this is
    // the alternate setting capable of the most audio bandwidth and thus the
    // highest sample rate / bit depth the device can support over USB HS.
    // std::stable_sort in the scanner ensures ties are broken by (iface, alt)
    // ascending, so the first max-bandwidth element encountered is also the
    // lowest-numbered one.
    const auto *best = &candidates[0];
    for (const auto &candidate : candidates) {
        if (candidate.effective_bytes_per_uframe > best->effective_bytes_per_uframe) {
            best = &candidate;
        }
    }

    PLOGI("uac2_select_best_endpoint: selected iface=%u alt=%u ep=0x%02X "
          "effective=%u bytes/uframe (nrCh=%u bitRes=%u subslot=%u)",
          best->interface_number,
          best->alt_setting,
          best->endpoint_address,
          best->effective_bytes_per_uframe,
          best->as_general.nr_channels,
          best->as_general.bit_resolution,
          best->as_general.subslot_size);

    return best;
}

const Uac2StreamingEndpointInfo *
uac2_select_endpoint_for_format(
        const std::vector<Uac2StreamingEndpointInfo> &candidates,
        int effective_bit_depth,
        int channel_count,
        int sample_rate_hz,
        bool require_exact_pcm)
{
    if (candidates.empty()) {
        PLOGW("uac2_select_endpoint_for_format: candidate list is empty — returning nullptr");
        return nullptr;
    }

    // Convert effective bit depth to target subslot byte width:
    //   16-bit → 2 bytes/subslot
    //   24-bit → 3 bytes/subslot  (packed S24LE after S32→S24 packing in the pump)
    //   32-bit → 4 bytes/subslot
    const auto target_subslot = static_cast<uint8_t>(effective_bit_depth / 8);

    // Minimum bytes per USB microframe required to sustain this stream:
    //   ceil(sampleRateHz / 8000) × subslotSize × channelCount
    // Uses hardware-matching subslotSize (not necessarily what the pump sends)
    // so the bandwidth check reflects what the real endpoint must carry.
    const auto required_bpuf = static_cast<uint16_t>(
            ((sample_rate_hz + 7999) / 8000) * target_subslot * channel_count);

    // ── Diagnostic: log all candidates before selection ───────────────────────
    PLOGI("uac2_select_endpoint_for_format: target bitDepth=%d subslot=%u "
          "channels=%d sampleRateHz=%d requiredBpuf=%u strictPcm=%s — scanning %zu candidate(s)",
          effective_bit_depth, static_cast<unsigned>(target_subslot),
          channel_count, sample_rate_hz,
          static_cast<unsigned>(required_bpuf), require_exact_pcm ? "yes" : "no",
          candidates.size());

    for (const auto &c : candidates) {
        if (c.as_general.is_present) {
            PLOGI("  candidate iface=%u alt=%u ep=0x%02X bpuf=%u | "
                  "subslot=%u bitRes=%u nrCh=%u meetsRequired=%s subslotMatch=%s bitResMatch=%s",
                  c.interface_number, c.alt_setting, c.endpoint_address,
                  c.effective_bytes_per_uframe,
                  c.as_general.subslot_size,
                  c.as_general.bit_resolution,
                  c.as_general.nr_channels,
                  (c.effective_bytes_per_uframe >= required_bpuf) ? "YES" : "no",
                  (c.as_general.subslot_size == target_subslot) ? "YES" : "no",
                  (c.as_general.bit_resolution == static_cast<uint8_t>(effective_bit_depth)) ? "YES" : "no");
        } else {
            PLOGI("  candidate iface=%u alt=%u ep=0x%02X bpuf=%u | "
                  "AS_GENERAL absent — no subslot/bitRes info  meetsRequired=%s",
                  c.interface_number, c.alt_setting, c.endpoint_address,
                  c.effective_bytes_per_uframe,
                  (c.effective_bytes_per_uframe >= required_bpuf) ? "YES" : "no");
        }
    }

    // ── Four-pass cascade selection ───────────────────────────────────────────
    //
    // Pass 1: exact subslotSize + exact bBitResolution + sufficient bandwidth
    //         → strongest guarantee that the DAC's hardware parser will accept
    //           the wire format without FSR ERROR or muting.
    //
    // Pass 2: exact subslotSize + sufficient bandwidth (any bBitResolution)
    //         → subslot byte width matches; minor bit-depth variance tolerated.
    //
    // Pass 3: subslotSize ≥ target + sufficient bandwidth
    //         → overprovisioned endpoint; acceptable only as a last resort when
    //           no exact-width endpoint exists at the requested sample rate.
    //
    // Pass 4: any endpoint with sufficient bandwidth — no format filtering.
    //         → complete fallback; also covers endpoints with absent AS_GENERAL.
    //
    // Within each pass: prefer the candidate with the **lowest alt_setting**
    // (most conservative endpoint, least likely vendor-specific over-provision).

    // Strict enhanced output requires the exact four-byte subslot. Pass 2 is
    // still valid because the writer MSB-aligns samples to the endpoint's
    // advertised bBitResolution and clears the remaining low padding bits.
    const int final_pass = require_exact_pcm ? 2 : 4;
    for (int pass = 1; pass <= final_pass; ++pass) {
        const Uac2StreamingEndpointInfo *best = nullptr;

        for (const auto &c : candidates) {
            // All passes require sufficient bandwidth.
            if (c.effective_bytes_per_uframe < required_bpuf) {
                continue;
            }

            if (require_exact_pcm) {
                const auto &as = c.as_general;
                const bool is_linear_pcm = as.is_present &&
                    as.format_type == UAC2_FORMAT_TYPE_I &&
                    (as.bm_formats & UAC_FORMAT_TYPE_I_PCM) != 0U;
                const bool channel_count_matches =
                    as.nr_channels == 0U || as.nr_channels == static_cast<uint8_t>(channel_count);
                const bool valid_resolution =
                    as.bit_resolution > 0U && as.bit_resolution <= target_subslot * 8U;
                if (!is_linear_pcm || !channel_count_matches ||
                    !valid_resolution || as.subslot_size != target_subslot) {
                    continue;
                }
            }

            if (!c.as_general.is_present) {
                // Endpoints without a class-specific descriptor only qualify
                // in pass 4 (blind fallback).
                if (pass < 4) {
                    continue;
                }
            } else {
                switch (pass) {
                    case 1:
                        // Exact subslot AND exact bBitResolution.
                        if (c.as_general.subslot_size != target_subslot) continue;
                        if (c.as_general.bit_resolution !=
                                static_cast<uint8_t>(effective_bit_depth)) continue;
                        break;
                    case 2:
                        // Exact subslot, any bBitResolution.
                        if (c.as_general.subslot_size != target_subslot) continue;
                        break;
                    case 3:
                        // Subslot at least as wide as target (overprovisioned).
                        if (c.as_general.subslot_size < target_subslot) continue;
                        break;
                    case 4:
                    default:
                        // No format filter — sufficient bandwidth is the only test.
                        break;
                }
            }

            // Tie-breaker: prefer the lowest alt_setting within this pass.
            if (best == nullptr || c.alt_setting < best->alt_setting) {
                best = &c;
            }
        }

        if (best != nullptr) {
            PLOGI("uac2_select_endpoint_for_format: SELECTED pass=%d "
                  "iface=%u alt=%u ep=0x%02X "
                  "subslot=%u bitRes=%u bpuf=%u/%u (required/effective)",
                  pass,
                  best->interface_number,
                  best->alt_setting,
                  best->endpoint_address,
                  best->as_general.subslot_size,
                  best->as_general.bit_resolution,
                  static_cast<unsigned>(required_bpuf),
                  best->effective_bytes_per_uframe);
            return best;
        }

        PLOGD("uac2_select_endpoint_for_format: pass %d: no qualifying candidate", pass);
    }

    PLOGE("uac2_select_endpoint_for_format: NO suitable endpoint found for "
          "bitDepth=%d subslot=%u sampleRateHz=%d channels=%d — "
          "requiredBpuf=%u exceeds all candidates",
          effective_bit_depth, static_cast<unsigned>(target_subslot),
          sample_rate_hz, channel_count,
          static_cast<unsigned>(required_bpuf));
    return nullptr;
}
