// ─────────────────────────────────────────────────────────────────────────────
// uac2_descriptor_parser.h
//
// USB Audio Class 2.0 descriptor scanner — public interface.
//
// Responsible for walking the libusb configuration / interface descriptor tree
// to locate every valid AudioStreaming alternate setting that carries an
// isochronous OUT endpoint.  The results are returned as an ordered vector of
// Uac2StreamingEndpointInfo values for the caller to select from.
//
// UAC2 class/subclass/protocol constants reference:
//   USB Device Class Definition for Audio Devices, Release 2.0
//   §A.1  Audio Function Subclass Codes — AUDIOSTREAMING = 0x02
//   §A.2  Audio Interface Protocol Codes — IP_VERSION_02_00 = 0x20
//
// Endianness:
//   libusb presents all descriptor integers in host-endian order.
//   Android ARM64 is little-endian; no byte-swap is required for libusb fields.
//   Raw bytes read from the `extra[]` class-specific descriptor blobs are
//   explicitly parsed via byte-safe accessors because they arrive in USB
//   (little-endian) byte order and must never be cast through a pointer.
// ─────────────────────────────────────────────────────────────────────────────

#pragma once

#include <cstdint>
#include <vector>

// Forward declaration — actual type lives in libusb/libusb.h.
// Callers must open the device before invoking any parser functions.
struct libusb_device_handle;

// ─────────────────────────────────────────────────────────────────────────────
// UAC2 protocol / descriptor constants
// ─────────────────────────────────────────────────────────────────────────────

/// USB Device Class for audio functions (bInterfaceClass = 0x01).
static constexpr uint8_t UAC_CLASS_AUDIO                = 0x01U;

/// AudioStreaming interface subclass (bInterfaceSubClass = 0x02).
static constexpr uint8_t UAC_SUBCLASS_AUDIOSTREAMING    = 0x02U;

/// UAC Version 2.0 interface protocol (bInterfaceProtocol = 0x20).
/// Distinguish from UAC1 (0x00) which is considerably more common on older DACs.
static constexpr uint8_t UAC_PROTOCOL_VERSION_2         = 0x20U;

/// Class-Specific interface descriptor type (bDescriptorType = 0x24).
static constexpr uint8_t UAC_CS_INTERFACE               = 0x24U;

/// AudioStreaming AS_GENERAL subtype (bDescriptorSubtype = 0x01).
/// UAC2 spec §4.9.2, Table 4-27.
static constexpr uint8_t UAC2_AS_DESC_SUBTYPE_GENERAL   = 0x01U;

/// AudioStreaming FORMAT_TYPE subtype (bDescriptorSubtype = 0x02).
/// UAC2 Data Formats spec §2.3.1.6.
static constexpr uint8_t UAC2_AS_DESC_SUBTYPE_FMT_TYPE  = 0x02U;

/// FORMAT_TYPE_I identifier (bFormatType inside FORMAT_TYPE descriptor = 0x01).
static constexpr uint8_t UAC2_FORMAT_TYPE_I             = 0x01U;

/// Minimum length of a UAC2 AS_GENERAL descriptor (16 bytes, no channels).
static constexpr uint8_t UAC2_AS_GENERAL_MIN_LEN        = 16U;

/// Fixed length of a UAC2 FORMAT_TYPE_I descriptor (6 bytes).
static constexpr uint8_t UAC2_FORMAT_TYPE_I_LEN         = 6U;

// ─────────────────────────────────────────────────────────────────────────────
// FORMAT_TYPE_IV — Raw Data (Native DSD / IEC 61937 container)
// ─────────────────────────────────────────────────────────────────────────────
//
// When bFormatType in a UAC2 AS_GENERAL descriptor equals 0x04, the alternate
// setting carries a raw bitstream rather than linearly-coded PCM samples.
// Real-world usage: Native DSD64/128/256/512 (DoP is PCM-based and does NOT
// use TYPE_IV; it uses TYPE_I with 24-bit or 32-bit subslot).
//
// Reference: USB Audio Class 2.0 — Audio Data Formats spec (ADF2), §2.3.4.
//
// Layout of a FORMAT_TYPE_IV descriptor in the extra[] blob:
//   [0]  bLength           = 4
//   [1]  bDescriptorType   = 0x24 (CS_INTERFACE)
//   [2]  bDescriptorSubtype = 0x02 (FORMAT_TYPE)
//   [3]  bFormatType       = 0x04 (FORMAT_TYPE_IV)
//
// ─────────────────────────────────────────────────────────────────────────────

/// FORMAT_TYPE_IV — Raw Data (Native DSD / IEC 61937) bFormatType value.
static constexpr uint8_t UAC2_FORMAT_TYPE_IV            = 0x04U;

/// Fixed length of a UAC2 FORMAT_TYPE_IV descriptor (4 bytes).
static constexpr uint8_t UAC2_FORMAT_TYPE_IV_LEN        = 4U;

// ─────────────────────────────────────────────────────────────────────────────
// bmFormats bit definitions — AS_GENERAL, bFormatType == TYPE_I
// ─────────────────────────────────────────────────────────────────────────────
// Source: ADF2 §2.3.1, Table 2-1 "Audio Data Format Type I Bit Allocations".
// The bitmap is a 32-bit LE field at offset 9 of the AS_GENERAL descriptor.
//
// NOTE: Some documentation and vendor SDKs cite UAC_FORMAT_TYPE_I_RAW_DATA as
// 0x1F.  This is INCORRECT.  0x1F is not a defined constant in the published
// USB Audio Data Formats 2.0 specification.  The correct value for the "Raw
// Data" bit in the TYPE_I bmFormats field is D31 = 0x80000000 (ADF2 §2.3.1,
// Table 2-1, last row).  Both values are checked during DSD detection so that
// non-conformant DACs advertising 0x1F are also caught.
// ─────────────────────────────────────────────────────────────────────────────

/// TYPE_I PCM (linear 16/20/24/32-bit).
static constexpr uint32_t UAC_FORMAT_TYPE_I_PCM         = 0x00000001U;
/// TYPE_I PCM8 (unsigned 8-bit).
static constexpr uint32_t UAC_FORMAT_TYPE_I_PCM8        = 0x00000002U;
/// TYPE_I IEEE_FLOAT (32-bit or 64-bit float).
static constexpr uint32_t UAC_FORMAT_TYPE_I_IEEE_FLOAT  = 0x00000004U;
/// TYPE_I ALAW (8-bit A-law companded).
static constexpr uint32_t UAC_FORMAT_TYPE_I_ALAW        = 0x00000008U;
/// TYPE_I MULAW (8-bit µ-law companded).
static constexpr uint32_t UAC_FORMAT_TYPE_I_MULAW       = 0x00000010U;
/// TYPE_I RAW_DATA (bit D31 per ADF2 §2.3.1 Table 2-1).
/// Set when the TYPE_I alternate setting also supports a native raw bitstream
/// (e.g., DSD words framed inside 32-bit PCM containers on some DACs).
/// THIS is the correct spec value — not 0x1F which some SDKs cite incorrectly.
static constexpr uint32_t UAC_FORMAT_TYPE_I_RAW_DATA    = 0x80000000U;
/// Non-standard RAW_DATA sentinel sometimes seen in non-conformant DAC firmware
/// (equivalent numerically to PCM|PCM8|IEEE_FLOAT|ALAW|MULAW combined).
/// Checked as a secondary heuristic only; NativeTypeIRaw classification
/// requires the correct 0x80000000 bit OR this sentinel for broad compatibility.
static constexpr uint32_t UAC_FORMAT_TYPE_I_RAW_DATA_NC = 0x0000001FU;

// ─────────────────────────────────────────────────────────────────────────────
// bmFormats bit definitions — AS_GENERAL, bFormatType == TYPE_IV
// ─────────────────────────────────────────────────────────────────────────────
// Source: ADF2 §2.3.4, Table 2-4 "Audio Data Format Type IV Bit Allocations".
// ─────────────────────────────────────────────────────────────────────────────

/// TYPE_IV RAW_DATA (bit D0) — raw DSD or IEC 61937 bitstream.
static constexpr uint32_t UAC_FORMAT_TYPE_IV_RAW_DATA   = 0x00000001U;

// ─────────────────────────────────────────────────────────────────────────────
// wMaxPacketSize field layout (USB 2.0 High-Speed, §9.6.6)
// ─────────────────────────────────────────────────────────────────────────────

/// Bits 10:0 of wMaxPacketSize carry the per-packet byte count.
static constexpr uint16_t USB_MPS_PACKET_SIZE_MASK      = 0x07FFU;

/// Bits 12:11 encode additional transactions per microframe:
///   0b00 → 1 transaction   (standard)
///   0b01 → 2 transactions  (high-bandwidth)
///   0b10 → 3 transactions  (high-bandwidth)
///   0b11 → reserved
static constexpr uint16_t USB_MPS_MULT_MASK             = 0x1800U;
static constexpr uint8_t  USB_MPS_MULT_SHIFT            = 11U;

// ─────────────────────────────────────────────────────────────────────────────
// Result types
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Parsed data from a UAC2 AS_GENERAL class-specific descriptor.
 *
 * Populated when the descriptor is found at the qualifying alternate setting.
 * Absent when the device omits the class-specific descriptor blob (non-standard
 * but does occur on some entry-level UAC2 DACs).
 *
 * Field byte offsets from UAC2 spec §4.9.2, Table 4-27 (total bLength = 16):
 *   [0]  bLength         — descriptor length in bytes (must be ≥ 16)
 *   [1]  bDescriptorType — 0x24 (CS_INTERFACE)
 *   [2]  bDescriptorSubtype — 0x01 (AS_GENERAL)
 *   [3]  bTerminalLink   — ID of the connected Output Terminal
 *   [4]  bmControls      — 1-byte control bitmap (not parsed here)
 *   [5]  bFormatType     — 0x01 = FORMAT_TYPE_I
 *   [6–9] bmFormats      — 4-byte LE bitmap of supported PCM sub-formats
 *   [10] bNrChannels     — number of physical audio channels
 *   [11–14] bmChannelConfig — 4-byte LE spatial channel bitmap
 *   [15] iChannelNames   — string descriptor index
 *
 * @property is_present       true when this struct was successfully populated.
 * @property terminal_link    bTerminalLink: ID of the connected Output Terminal.
 * @property format_type      bFormatType (0x01 = FORMAT_TYPE_I).
 * @property bm_formats       bmFormats [LE]: sub-format capability bitmap.
 * @property nr_channels      bNrChannels: number of physical output channels.
 * @property bm_channel_config bmChannelConfig [LE]: spatial channel bitmap.
 * @property subslot_size     bSubslotSize from FORMAT_TYPE_I (bytes per subslot).
 * @property bit_resolution   bBitResolution from FORMAT_TYPE_I (effective bits).
 */
struct Uac2AsGeneralInfo {
    bool     is_present        = false;   ///< true when AS_GENERAL was parsed
    uint8_t  terminal_link     = 0;       ///< bTerminalLink
    uint8_t  format_type       = 0;       ///< bFormatType (0x01=TYPE_I, 0x04=TYPE_IV)
    uint32_t bm_formats        = 0;       ///< bmFormats [LE 32-bit parsed]
    uint8_t  nr_channels       = 0;       ///< bNrChannels
    uint32_t bm_channel_config = 0;       ///< bmChannelConfig [LE 32-bit parsed]
    uint8_t  subslot_size      = 0;       ///< bSubslotSize from FORMAT_TYPE_I
    uint8_t  bit_resolution    = 0;       ///< bBitResolution from FORMAT_TYPE_I
    /// true when a FORMAT_TYPE_IV (Raw Data) descriptor was found in extra[].
    /// This is the definitive confirmation of native DSD capability beyond what
    /// the AS_GENERAL bFormatType field alone indicates.  When the AS_GENERAL
    /// descriptor declares bFormatType == 0x04 AND this flag is true, the
    /// alternate setting definitively supports a native raw bitstream.
    bool     has_format_type_iv = false;
};

/**
 * All critical information about a UAC2 AudioStreaming alternate setting that
 * carries a valid isochronous OUT endpoint.
 *
 * One instance is produced per qualifying (interface, alt_setting) pair.
 * Only alternate settings with wMaxPacketSize > 0 appear here; alt setting 0
 * (the zero-bandwidth idle setting) is explicitly excluded.
 *
 * ### wMaxPacketSize interpretation (USB 2.0 HS §9.6.6)
 *
 * The raw field encodes two sub-fields for high-bandwidth isochronous pipes:
 *   bits 10:0 — base packet size in bytes
 *   bits 12:11 — additional transaction multiplier (0 = 1×, 1 = 2×, 2 = 3×)
 *
 * Effective bytes per 125 µs USB microframe:
 *   effective = (raw & 0x07FF) × ((raw >> 11 & 0x03) + 1)
 *
 * Example: wMaxPacketSize = 0x0C00 (FiiO K9 at DSD256/352.8 kHz)
 *   bytes = (0x0C00 & 0x07FF) = 0 ... actually: 0x03 = 3, mult = 1
 * → Must always use effective_bytes_per_uframe for bandwidth decisions.
 *
 * @property interface_number         bInterfaceNumber of the streaming interface.
 * @property alt_setting              bAlternateSetting index (always ≥ 1).
 * @property endpoint_address         bEndpointAddress (OUT: bit 7 = 0).
 * @property max_packet_size_raw      wMaxPacketSize as returned by libusb (host-endian).
 * @property effective_bytes_per_uframe  Actual payload capacity per microframe.
 * @property endpoint_interval        bInterval — service interval (microframes, 2^(N-1)).
 * @property sync_type                Isochronous sync type (bits 3:2 of bmAttributes).
 * @property as_general               Parsed UAC2 class-specific descriptor data.
 */
struct Uac2StreamingEndpointInfo {
    uint8_t  interface_number           = 0;
    uint8_t  alt_setting                = 0;
    uint8_t  endpoint_address           = 0;
    uint16_t max_packet_size_raw        = 0;   ///< wMaxPacketSize, host-endian
    uint16_t effective_bytes_per_uframe = 0;   ///< (raw & 0x7FF) × (mult + 1)
    uint8_t  endpoint_interval          = 0;   ///< bInterval
    uint8_t  sync_type                  = 0;   ///< (bmAttributes >> 2) & 0x03
    Uac2AsGeneralInfo as_general;
};

// ─────────────────────────────────────────────────────────────────────────────
// Public API
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Scan the active USB configuration for all UAC2 AudioStreaming isochronous
 * OUT endpoints.
 *
 * The function iterates every (interface, alternate setting, endpoint) triple
 * in the device's active configuration and collects candidates that satisfy
 * ALL of the following strict UAC2 filters:
 *
 *   ① bInterfaceClass    == 0x01  (LIBUSB_CLASS_AUDIO)
 *   ② bInterfaceSubClass == 0x02  (AudioStreaming)
 *   ③ bInterfaceProtocol == 0x20  (UAC Version 2.0)
 *   ④ bmAttributes bits 1:0 == 0x01  (Isochronous transfer type)
 *   ⑤ bEndpointAddress bit 7 == 0x00  (OUT direction — host-to-device)
 *   ⑥ wMaxPacketSize > 0  (excludes zero-bandwidth AltSetting 0)
 *
 * Additionally attempts to parse the UAC2 class-specific AS_GENERAL and
 * FORMAT_TYPE_I descriptors from the interface's `extra[]` data.  Parse
 * failures are logged but do not disqualify the endpoint.
 *
 * Results are ordered by (interface_number ASC, alt_setting ASC).
 *
 * @param device_handle  Open libusb device handle from Step 1.
 *                       Must not be null.
 * @return               Zero or more endpoint descriptors.  Returns an empty
 *                       vector when:
 *                         – The device has no UAC2 AS interface.
 *                         – libusb_get_active_config_descriptor() fails.
 *                         – Every AS endpoint violates one of the six filters.
 */
std::vector<Uac2StreamingEndpointInfo>
uac2_find_streaming_endpoints(libusb_device_handle *device_handle);

/**
 * Select the highest-bandwidth UAC2 endpoint from a pre-scanned list.
 *
 * "Highest bandwidth" is defined as the entry with the greatest
 * `effective_bytes_per_uframe`, which corresponds to the alternate setting
 * carrying the most audio data per microframe and thus the highest achievable
 * sample rate / bit depth.
 *
 * When multiple endpoints share the same effective bandwidth, the one with
 * the lower (interface_number, alt_setting) pair is preferred — this produces
 * deterministic selection on DACs that expose two identical paths.
 *
 * @param candidates  Non-empty vector returned by uac2_find_streaming_endpoints().
 * @return            Const pointer to the best entry inside `candidates`,
 *                    or nullptr if the vector is empty.
 */
const Uac2StreamingEndpointInfo *
uac2_select_best_endpoint(const std::vector<Uac2StreamingEndpointInfo> &candidates);

/**
 * Select the UAC2 endpoint whose format descriptor best matches the source audio.
 *
 * Uses a four-pass cascade to find the most precise format match:
 *
 * | Pass | Criteria |
 * |------|----------|
 * | 1 | `bSubslotSize == target_subslot` AND `bBitResolution == effective_bit_depth` AND sufficient bandwidth |
 * | 2 | `bSubslotSize == target_subslot` AND sufficient bandwidth (any bBitResolution) |
 * | 3 | `bSubslotSize >= target_subslot` AND sufficient bandwidth (overprovisioned endpoint) |
 * | 4 | Sufficient bandwidth only — no format filtering (last resort) |
 *
 * `target_subslot` is derived from `effective_bit_depth / 8`:
 *   - 16-bit → 2 bytes/subslot
 *   - 24-bit (packed S24LE, after S32→S24 packing) → 3 bytes/subslot
 *   - 32-bit → 4 bytes/subslot
 *
 * Among candidates tied within a pass, the one with the **lowest alt_setting**
 * is preferred (most conservative, least likely to over-provision).
 *
 * All candidates are logged at INFO level with their parsed `bSubslotSize`,
 * `bBitResolution`, and effective bandwidth before selection.
 *
 * @param candidates         Non-empty vector from uac2_find_streaming_endpoints().
 * @param effective_bit_depth Wire bit depth after any packing (16, 24, or 32).
 * @param channel_count      Number of audio channels (e.g., 2 for stereo).
 * @param sample_rate_hz     Target sample rate in Hz (e.g., 44100, 192000).
 * @param require_exact_pcm  Restrict selection to Type-I linear PCM with the
 *   exact requested subslot width, a valid advertised bit resolution, and a
 *   compatible channel count.
 * @return                   Const pointer to the selected entry inside `candidates`,
 *                           or nullptr if the vector is empty or no endpoint has
 *                           sufficient bandwidth for the requested format.
 */
const Uac2StreamingEndpointInfo *
uac2_select_endpoint_for_format(
        const std::vector<Uac2StreamingEndpointInfo> &candidates,
        int effective_bit_depth,
        int channel_count,
        int sample_rate_hz,
        bool require_exact_pcm = false);
