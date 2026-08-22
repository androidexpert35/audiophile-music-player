#include "uac2_clock_control.h"

#include <android/log.h>

#include <chrono>
#include <thread>

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

// ── Clock candidate / retry policy ──────────────────────────────────────────
/** Upper bound on Clock Source entities collected from one configuration. */
constexpr int kMaxClockSourceIds     = 8;
/** UAC2 reference-design default bClockID — used only when parsing finds none. */
constexpr int kUac2ReferenceClockId  = 1;
/**
 * SET_CUR attempts per candidate before that clock entity counts as refused.
 *
 * The first attempt after a track change can land while the DAC is still
 * re-locking its PLL from the previous teardown; such firmwares NAK or STALL
 * once and then accept the request. Treating that transient as a hard failure
 * costs the whole direct-USB session, so it is retried rather than reported.
 */
constexpr int kClockSetAttemptsPerId = 3;
/** Pause between attempts — long enough for a PLL re-lock to settle. */
constexpr int kClockRetryDelayMs     = 25;

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

namespace {

/**
 * Collects every UAC2 Clock Source `bClockID` declared by the active
 * configuration, in descriptor order, and reports the Audio Control interface.
 *
 * Multi-clock DACs are the reason this returns a list rather than the first
 * hit: an internal PLL plus an S/PDIF or word-clock source is a common layout,
 * and the first Clock Source descriptor is not necessarily the entity that
 * feeds the USB streaming path. Every ID returned here is one the device
 * itself declared, so probing them cannot address a non-existent entity.
 *
 * @param handle           Open libusb device handle.
 * @param ids_out          Caller-owned buffer receiving the IDs.
 * @param max_ids          Capacity of [ids_out].
 * @param ac_interface_out Optional out-param receiving the bInterfaceNumber of
 *                         the first Audio Control interface, or -1 when none
 *                         exists. Populated even when no CLOCK_SOURCE follows.
 * @return Number of IDs written to [ids_out]; 0 when none were found.
 */
int collect_clock_source_ids(
        libusb_device_handle *handle,
        uint8_t *ids_out,
        int max_ids,
        int *ac_interface_out) noexcept
{
    if (ac_interface_out != nullptr) {
        *ac_interface_out = -1;
    }
    if (handle == nullptr || ids_out == nullptr || max_ids <= 0) {
        return 0;
    }

    libusb_device *dev = libusb_get_device(handle);
    if (!dev) {
        CLKLOGW("collect_clock_source_ids: libusb_get_device() returned null");
        return 0;
    }

    libusb_config_descriptor *cfg = nullptr;
    if (libusb_get_active_config_descriptor(dev, &cfg) != LIBUSB_SUCCESS || !cfg) {
        CLKLOGW("collect_clock_source_ids: libusb_get_active_config_descriptor() failed");
        return 0;
    }

    int count = 0;

    for (uint8_t i = 0; i < cfg->bNumInterfaces && count < max_ids; ++i) {
        const libusb_interface &iface = cfg->interface[i];
        for (int a = 0; a < iface.num_altsetting && count < max_ids; ++a) {
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
                CLKLOGI("collect_clock_source_ids: Audio Control interface = %d",
                        *ac_interface_out);
            }

            // Walk the class-specific (extra) descriptor bytes.
            const uint8_t *p    = alt.extra;
            int            left = alt.extra_length;

            while (left >= 3 && count < max_ids) {
                const uint8_t bLen  = p[0];
                const uint8_t bType = p[1];
                const uint8_t bSub  = p[2];

                if (bLen < 3 || static_cast<int>(bLen) > left) break;  // malformed

                if (bType == kUac2CsInterface &&
                    bSub  == kUac2CsSubtypeClockSrc &&
                    bLen  >= kUac2ClockSourceMinLen &&
                    p[3]  != 0U)
                {
                    // p[3] = bClockID per the UAC2 Clock Source Descriptor.
                    // Alternate settings of the same AC interface repeat the
                    // class descriptors verbatim, hence the duplicate check.
                    bool duplicate = false;
                    for (int k = 0; k < count; ++k) {
                        if (ids_out[k] == p[3]) { duplicate = true; break; }
                    }
                    if (!duplicate) {
                        ids_out[count++] = p[3];
                        CLKLOGI("collect_clock_source_ids: found bClockID=%u "
                                "(iface=%u alt=%d extra_offset=%td)",
                                static_cast<unsigned>(p[3]), i, a,
                                static_cast<ptrdiff_t>(p - alt.extra));
                    }
                }

                p    += bLen;
                left -= static_cast<int>(bLen);
            }
        }
    }

    libusb_free_config_descriptor(cfg);
    return count;
}

} // namespace

int uac2_find_clock_source_id(
        libusb_device_handle *handle,
        int *ac_interface_out) noexcept
{
    uint8_t ids[kMaxClockSourceIds] = {0};
    const int count =
            collect_clock_source_ids(handle, ids, kMaxClockSourceIds, ac_interface_out);

    if (count <= 0) {
        CLKLOGW("uac2_find_clock_source_id: no CLOCK_SOURCE descriptor found in "
                "config — caller will use the reference fallback ID");
        return -1;
    }

    return static_cast<int>(ids[0]);
}

int uac2_force_clock_sample_rate(
        libusb_device_handle *handle,
        uint8_t control_interface,
        int parsed_clock_id,
        uint32_t sample_rate_hz,
        int *winning_id_out) noexcept
{
    // ── Candidate list: descriptor-authoritative, never speculative ──────────
    //
    // Order: the parsed bClockID supplied by the caller first, then every OTHER
    // Clock Source entity the configuration descriptor actually declares. The
    // UAC2 reference default (1) is appended only when the descriptor walk
    // produced nothing at all.
    //
    // Speculative IDs (41/40/10/11/12) stay excluded, and a hardcoded 1 is no
    // longer appended behind a successfully parsed ID either: a SET_CUR
    // addressed to a clock entity the firmware does not expose STALLs EP0, and
    // on XMOS/FiiO firmware that stall wedges the control pipe for the whole
    // remaining session — every later SET_CUR then fails with
    // LIBUSB_ERROR_PIPE until the DAC is re-plugged. Every ID attempted here is
    // one the device declared, so covering multi-clock layouts costs no such
    // risk.
    uint8_t declared[kMaxClockSourceIds] = {0};
    const int declared_count =
            collect_clock_source_ids(handle, declared, kMaxClockSourceIds, nullptr);

    constexpr int kMaxCandidates = kMaxClockSourceIds + 1;
    int candidates[kMaxCandidates] = {0};
    int candidate_count = 0;

    const auto push_candidate = [&](int clock_id) {
        if (clock_id <= 0 || clock_id > 0xFF)  return;
        if (candidate_count >= kMaxCandidates) return;
        for (int k = 0; k < candidate_count; ++k) {
            if (candidates[k] == clock_id) return;
        }
        candidates[candidate_count++] = clock_id;
    };

    push_candidate(parsed_clock_id);
    for (int k = 0; k < declared_count; ++k) {
        push_candidate(static_cast<int>(declared[k]));
    }
    if (candidate_count == 0) {
        push_candidate(kUac2ReferenceClockId);
    }

    CLKLOGI("uac2_force_clock_sample_rate: target=%u Hz parsed_id=%d ctrl_iface=%u "
            "candidates=%d declared=%d",
            sample_rate_hz, parsed_clock_id,
            static_cast<unsigned>(control_interface),
            candidate_count, declared_count);

    // ── Attempt loop ─────────────────────────────────────────────────────────
    //
    // Each candidate is retried kClockSetAttemptsPerId times with a short pause
    // in between. A DAC still re-locking its PLL after the previous track's
    // teardown NAKs or STALLs the first SET_CUR and accepts the retry a few
    // milliseconds later. Without the retry that purely transient failure is
    // reported to the caller as a hard clock failure, which abandons the entire
    // direct-USB session and silently demotes bit-perfect playback to the
    // platform mixer for the rest of the track.
    int last_error = LIBUSB_ERROR_OTHER;

    for (int c = 0; c < candidate_count; ++c) {
        const int clock_id = candidates[c];

        for (int attempt = 1; attempt <= kClockSetAttemptsPerId; ++attempt) {
            const int ret = uac2_set_clock_sample_rate(
                    handle,
                    control_interface,
                    static_cast<uint8_t>(clock_id),
                    sample_rate_hz);

            if (ret == 4) {
                CLKLOGI("uac2_force_clock_sample_rate: clock set OK — id=%d "
                        "rate=%u Hz (attempt %d/%d)",
                        clock_id, sample_rate_hz, attempt, kClockSetAttemptsPerId);
                if (winning_id_out != nullptr) {
                    *winning_id_out = clock_id;
                }
                return ret;
            }

            last_error = ret;
            CLKLOGW("uac2_force_clock_sample_rate: clockId=%d attempt %d/%d failed: "
                    "%s (%d)",
                    clock_id, attempt, kClockSetAttemptsPerId,
                    libusb_error_name(ret), ret);

            if (attempt < kClockSetAttemptsPerId) {
                std::this_thread::sleep_for(
                        std::chrono::milliseconds(kClockRetryDelayMs));
            }
        }
    }

    // Every declared clock entity refused the rate on every attempt. The caller
    // must treat this as fatal for the direct-USB session: streaming ISO data
    // against an unprogrammed PLL plays at the wrong rate, which on some
    // firmware persists until the DAC is re-plugged.
    CLKLOGE("uac2_force_clock_sample_rate: all %d declared clock ID(s) failed on "
            "ctrl_iface=%u after %d attempts each (last error %d) — aborting "
            "direct-USB clock setup",
            candidate_count, static_cast<unsigned>(control_interface),
            kClockSetAttemptsPerId, last_error);
    return -1;
}
