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

int uac2_find_clock_source_id(
        libusb_device_handle *handle,
        int *ac_interface_out) noexcept
{
    if (ac_interface_out != nullptr) {
        *ac_interface_out = -1;
    }

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

            // Record the FIRST Audio Control interface number even when the
            // Clock Source descriptor is missing/unparseable: composite
            // devices (BT/USB combo DACs) do not keep Audio Control at
            // interface 0, and every clock SET_CUR must address the real AC
            // interface in wIndex or the request lands on another function.
            if (ac_interface_out != nullptr && *ac_interface_out < 0) {
                *ac_interface_out = static_cast<int>(alt.bInterfaceNumber);
                CLKLOGI("uac2_find_clock_source_id: Audio Control interface = %d",
                        *ac_interface_out);
            }

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
        uint8_t control_interface,
        int parsed_clock_id,
        uint32_t sample_rate_hz,
        int *winning_id_out) noexcept
{
    // Minimal, descriptor-driven candidate list — the parsed bClockID first,
    // then the UAC2 reference default (1).  Speculative IDs (41/40/10/11/12)
    // are deliberately excluded: a SET_CUR to a non-existent clock entity
    // STALLs EP0 and, on XMOS/FiiO firmware, wedges the control pipe for the
    // remainder of the session (every later SET_CUR fails with
    // LIBUSB_ERROR_PIPE until the DAC is re-plugged).  Sentinel 0 terminates
    // the list.  See uac2_clock_control.h and the Step-3 commentary in
    // usb_teardown.cpp for the field history behind this restriction.
    const int candidates[] = { parsed_clock_id, 1, 0 };

    CLKLOGI("uac2_force_clock_sample_rate: target=%u Hz parsed_id=%d ctrl_iface=%u",
            sample_rate_hz, parsed_clock_id,
            static_cast<unsigned>(control_interface));

    // Track already-tried IDs so exact duplicates are skipped.
    int tried[4] = {0};
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
                control_interface,
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

    // Every candidate exhausted — the DAC is likely not UAC2, its Audio
    // Control interface differs from the one supplied, or the firmware is
    // temporarily rejecting control transfers (e.g. mid PLL re-lock).  The
    // caller must treat this as fatal for the direct-USB session: streaming
    // ISO data against an unprogrammed PLL plays at the wrong rate.
    CLKLOGE("uac2_force_clock_sample_rate: ALL clock IDs failed "
            "(tried %d %d on ctrl_iface=%u) — aborting direct-USB clock setup",
            tried[0], tried[1], static_cast<unsigned>(control_interface));
    return -1;
}
