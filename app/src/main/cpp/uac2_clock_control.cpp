#include "uac2_clock_control.h"

#include <android/log.h>

#include "libusb/libusb.h"

static constexpr const char *CLK_TAG = "UsbAudioBridge";

#define CLKLOGD(...) __android_log_print(ANDROID_LOG_DEBUG, CLK_TAG, __VA_ARGS__)
#define CLKLOGI(...) __android_log_print(ANDROID_LOG_INFO,  CLK_TAG, __VA_ARGS__)
#define CLKLOGW(...) __android_log_print(ANDROID_LOG_WARN,  CLK_TAG, __VA_ARGS__)
#define CLKLOGE(...) __android_log_print(ANDROID_LOG_ERROR, CLK_TAG, __VA_ARGS__)

namespace {

// ── Descriptor byte constants ────────────────────────────────────────────────
constexpr uint8_t kUac2CsInterface        = 0x24U;
constexpr uint8_t kUac2CsSubtypeClockSrc  = 0x0AU;
constexpr int     kUac2ClockSourceMinLen  = 8;        // bLength per spec
constexpr uint8_t kUsbClassAudio          = 0x01U;
constexpr uint8_t kUsbSubclassAudioControl = 0x01U;

// ── SET_CUR control constants (UAC2 §5.2.1 / §5.2.5.1) ──────────────────────
constexpr uint8_t  kRequestSetCur         = 0x01U;
// wValue = CS_SAM_FREQ_CONTROL (0x01) in the high byte; channel 0 in the low.
constexpr uint16_t kSampleFrequencyControl = 0x0100U;
// bmRequestType 0x21 = Host→Device | Class | Interface.  The Clock Source
// entity is addressed through the Audio Control INTERFACE recipient (UAC2
// §5.2.1 Table 5-2); the Endpoint-recipient encoding (0x22) is STALLed by
// strict firmwares (FiiO, XMOS, ESS).
constexpr uint8_t  kHostToClassInterface  = 0x21U;
constexpr unsigned int kControlTimeoutMs  = 1'000U;

} // namespace

int uac2_set_clock_sample_rate(
        libusb_device_handle *handle,
        uint8_t control_interface,
        uint8_t clock_source_id,
        uint32_t sample_rate_hz) noexcept {
    if (!handle || clock_source_id == 0U || sample_rate_hz == 0U) {
        return LIBUSB_ERROR_INVALID_PARAM;
    }

    // UAC2 §5.2.5.1: dCUR is a 4-byte little-endian frequency field.
    unsigned char data[4] = {
        static_cast<unsigned char>(sample_rate_hz & 0xFFU),
        static_cast<unsigned char>((sample_rate_hz >> 8U) & 0xFFU),
        static_cast<unsigned char>((sample_rate_hz >> 16U) & 0xFFU),
        static_cast<unsigned char>((sample_rate_hz >> 24U) & 0xFFU),
    };

    // wIndex = (bClockID << 8) | bInterfaceNumber of the Audio Control iface.
    const uint16_t index = static_cast<uint16_t>(
            (static_cast<uint16_t>(clock_source_id) << 8U) |
            control_interface);
    return libusb_control_transfer(
            handle,
            kHostToClassInterface,
            kRequestSetCur,
            kSampleFrequencyControl,
            index,
            data,
            sizeof(data),
            kControlTimeoutMs);
}

int uac2_find_clock_source_id(libusb_device_handle *handle) noexcept
{
    libusb_device *dev = libusb_get_device(handle);
    if (!dev) {
        CLKLOGW("uac2_find_clock_source_id: libusb_get_device() returned null");
        return -1;
    }

    libusb_config_descriptor *cfg = nullptr;
    if (libusb_get_active_config_descriptor(dev, &cfg) != LIBUSB_SUCCESS || !cfg) {
        CLKLOGW("uac2_find_clock_source_id: libusb_get_active_config_descriptor() failed");
        return -1;
    }

    int found_id = -1;

    for (uint8_t i = 0; i < cfg->bNumInterfaces && found_id < 0; ++i) {
        const libusb_interface &iface = cfg->interface[i];
        for (int a = 0; a < iface.num_altsetting && found_id < 0; ++a) {
            const libusb_interface_descriptor &alt = iface.altsetting[a];

            // Only examine Audio Control interfaces.
            if (alt.bInterfaceClass    != kUsbClassAudio)           continue;
            if (alt.bInterfaceSubClass != kUsbSubclassAudioControl) continue;

            // Walk the class-specific (extra) descriptor bytes.
            const uint8_t *p    = alt.extra;
            int            left = alt.extra_length;

            while (left >= 3) {
                const uint8_t bLen  = p[0];
                const uint8_t bType = p[1];
                const uint8_t bSub  = p[2];

                if (bLen < 3 || static_cast<int>(bLen) > left) break;  // malformed

                if (bType == kUac2CsInterface &&
                    bSub  == kUac2CsSubtypeClockSrc &&
                    bLen  >= kUac2ClockSourceMinLen)
                {
                    // p[3] = bClockID per the UAC2 Clock Source Descriptor.
                    found_id = static_cast<int>(p[3]);
                    CLKLOGI("uac2_find_clock_source_id: found bClockID=%d "
                            "(iface=%u alt=%d extra_offset=%td)",
                            found_id, i, a,
                            static_cast<ptrdiff_t>(p - alt.extra));
                    break;
                }

                p    += bLen;
                left -= static_cast<int>(bLen);
            }
        }
    }

    libusb_free_config_descriptor(cfg);

    if (found_id < 0) {
        CLKLOGW("uac2_find_clock_source_id: no CLOCK_SOURCE descriptor found in "
                "config — will use fallback ID");
    }

    return found_id;
}

int uac2_force_clock_sample_rate(
        libusb_device_handle *handle,
        int parsed_clock_id,
        uint32_t sample_rate_hz,
        int *winning_id_out) noexcept
{
    // Ranked candidate list — parsed descriptor ID first, then empirically
    // derived fallbacks covering every FiiO KA variant and major XMOS/Cirrus
    // reference designs seen in the field.  Sentinel 0 terminates the list.
    const int candidates[] = { parsed_clock_id, 41, 40, 10, 11, 12, 1, 0 };

    // The Audio Control interface is interface 0 for single-function UAC2 DACs
    // (USB Audio spec §3.4).  Multi-function devices are covered by the
    // caller's explicit-interface fallback path.
    constexpr uint8_t kCtrlIface = 0U;

    CLKLOGI("uac2_force_clock_sample_rate: target=%u Hz parsed_id=%d",
            sample_rate_hz, parsed_clock_id);

    // Track already-tried IDs so exact duplicates are skipped.
    int tried[8] = {0};
    int tried_count = 0;

    for (const int *p = candidates; *p != 0; ++p) {
        const int clock_id = *p;

        if (clock_id <= 0) continue;
        bool already_tried = false;
        for (int k = 0; k < tried_count; ++k) {
            if (tried[k] == clock_id) { already_tried = true; break; }
        }
        if (already_tried) continue;
        tried[tried_count++] = clock_id;

        const int ret = uac2_set_clock_sample_rate(
                handle,
                kCtrlIface,
                static_cast<uint8_t>(clock_id),
                sample_rate_hz);

        if (ret == 4) {
            CLKLOGI("uac2_force_clock_sample_rate: clock set OK — id=%d rate=%u Hz",
                    clock_id, sample_rate_hz);
            if (winning_id_out != nullptr) {
                *winning_id_out = clock_id;
            }
            return ret;
        }

        // Non-fatal by design — try the next candidate.
        CLKLOGW("uac2_force_clock_sample_rate: clockId=%d failed: %s (%d) — trying next",
                clock_id, libusb_error_name(ret), ret);
    }

    // Every candidate exhausted — the DAC is likely not UAC2, or its Audio
    // Control interface is on a non-zero interface number.
    CLKLOGE("uac2_force_clock_sample_rate: ALL clock IDs failed "
            "(tried %d %d %d %d %d %d) — FSR ERROR / hardware mute likely",
            tried[0], tried[1], tried[2], tried[3], tried[4], tried[5]);
    return -1;
}
