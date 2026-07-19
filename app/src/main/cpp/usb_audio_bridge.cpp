// ─────────────────────────────────────────────────────────────────────────────
// usb_audio_bridge.cpp
//
// JNI implementation for UsbAudioBridge.kt — user-space USB Audio Class 2.0
// driver built on libusb, bypassing Android AudioFlinger / AudioTrack / AAudio
// for bit-perfect audio delivery directly to an external USB DAC.
//
// ┌─────────────────┐       JNI       ┌──────────────────────────────────────┐
// │  Kotlin / Java  │ ─────────────── │  user-space USB Audio Driver (C++)   │
// │                 │                 │                                       │
// │  UsbManager     │                 │  Context init + FD wrap               │
// │  openDevice()   │──── fd (int) ──▶│  libusb_init / set_option /           │
// │                 │                 │  libusb_wrap_sys_device               │
// └─────────────────┘                 └──────────────────────────────────────┘
//
// Endianness contract:
//   Android ARM64 is little-endian.  The USB specification §9 mandates that
//   all multi-byte descriptor fields are little-endian.  No byte-swap is
//   required for descriptor parsing on the target hardware.  Any field
//   originating from a USB descriptor is annotated [LE] in comments.
//
// Thread safety:
//   nativeInitWithFileDescriptor:  Called once from the Kotlin audio thread
//     before any transfer is submitted.  Not re-entrant by design.
//   nativeRelease:  Called from Kotlin once playback is fully stopped and
//     all in-flight transfers have been cancelled.  Not re-entrant.
//   Transfer callbacks: Fired by the libusb event thread.
//     Must be lock-free and non-blocking.
//
// Memory ownership:
//   The UsbDriverContext struct is heap-allocated here and its pointer is
//   returned to Kotlin as a jlong handle.  Kotlin must pass the handle back
//   to nativeRelease; after that call the pointer is invalid.
// ─────────────────────────────────────────────────────────────────────────────

#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <array>
#include <cstring>
#include <limits>
#include <memory>
#include <new>
#include <time.h>    // nanosleep — cold-boot idle window in nativeStartDsdPlayback

#include "libusb/libusb.h"
#include "uac2_descriptor_parser.h"   // UAC2 endpoint scanner + format-aware selector
#include "uac2_clock_control.h"       // clock discovery / probing / SET_CUR encoder
#include "usb_device_controller.h"
#include "usb_driver_context.h"       // UsbDriverContext — shared aggregate
#include "usb_handle_validation.h"    // shared jlong handle validation (MTE-safe)
#include "usb_session_transport.h"    // shared ring + event-thread setup
#include "usb_teardown.h"             // safe teardown sequence
#include "dsd_playback_manager.h"     // Native DSD → DoP fallback

// ─── Logging macros ───────────────────────────────────────────────────────────

static constexpr const char *TAG = "UsbAudioBridge";

#define ULOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define ULOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define ULOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define ULOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ─── JNI return codes (mirrored in UsbAudioBridge.kt) ─────────────────────────

/// All phases succeeded; the returned handle is valid.
[[maybe_unused]] static constexpr jlong USB_BRIDGE_OK = 0LL;

/// libusb_init() failed; context allocation or platform error.
static constexpr jlong ERR_LIBUSB_INIT       = -1LL;

/// libusb_set_option(LIBUSB_OPTION_NO_DEVICE_DISCOVERY) failed.
[[maybe_unused]] static constexpr jlong ERR_NO_DISCOVERY_OPT  = -2LL;

/// libusb_wrap_sys_device() failed; FD may be invalid or already claimed.
static constexpr jlong ERR_WRAP_SYS_DEVICE   = -3LL;

/// Heap allocation for the driver context struct failed (OOM).
static constexpr jlong ERR_OUT_OF_MEMORY     = -4LL;

// UsbDriverContext is defined in usb_driver_context.h and shared with
// usb_teardown.cpp.  No local redefinition needed here.

// ─── Teardown constants ───────────────────────────────────────────────────────
// kCancellationTimeout and kCancellationPollInterval live in usb_teardown.cpp.

// ─── Handle validation ────────────────────────────────────────────────────────

// The sentinel range must track the ERR_* codes above; the shared validator
// documents the ARM64 MTE / HWASan tagged-pointer contract.
static_assert(ERR_OUT_OF_MEMORY == kUsbDriverLowestErrorSentinel,
              "update kUsbDriverLowestErrorSentinel in usb_handle_validation.h");

/// Returns true when [h] is safe to cast back to UsbDriverContext*.
/// Mirrors `UsbAudioBridge.isValidHandle()` on the Kotlin side.
static inline bool is_valid_handle(jlong h) noexcept {
    return is_valid_native_handle(static_cast<int64_t>(h),
                                  kUsbDriverLowestErrorSentinel);
}

// Ring capacity: kDefaultRingBufferBytes in usb_session_transport.h.

/**
 * Seeds a DSD session with transport-valid silence before ISO submission.
 *
 * Native DSD uses the conventional 0x69 idle pattern. DoP wraps the same
 * pattern in alternating 0x05/0xFA markers so a DoP-only DAC never observes
 * marker-less PCM zeros while the real decoder producer is attaching.
 */
static void prefill_dsd_silence(
        SpscRingBuffer *ring,
        DsdWireMode mode) noexcept {
    if (!ring) return;

    constexpr std::size_t kFrameBytes = 8U;
    constexpr std::size_t kFrames = 256U;
    std::array<uint8_t, kFrameBytes * kFrames> chunk{};

    if (mode == DsdWireMode::Native) {
        chunk.fill(0x69U);
    } else {
        uint8_t marker = 0x05U;
        for (std::size_t frame = 0; frame < kFrames; ++frame) {
            const std::size_t offset = frame * kFrameBytes;
            for (std::size_t channel = 0; channel < 2U; ++channel) {
                const std::size_t word = offset + channel * 4U;
                chunk[word + 0U] = 0x00U;
                chunk[word + 1U] = 0x69U;
                chunk[word + 2U] = 0x69U;
                chunk[word + 3U] = marker;
            }
            marker ^= 0xFFU;
        }
    }

    const std::size_t target = ring->capacity() / 2U;
    std::size_t filled = 0U;
    while (filled + chunk.size() <= target && ring->push(chunk.data(), chunk.size())) {
        filled += chunk.size();
    }
}

// Teardown implementation: see usb_teardown.cpp / usb_teardown.h.

// ─────────────────────────────────────────────────────────────────────────────
// nativeInitWithFileDescriptor — initialise libusb and open the device via FD
// ─────────────────────────────────────────────────────────────────────────────

/**
 * nativeInitWithFileDescriptor — initialise the user-space USB audio driver.
 *
 * Called once by UsbAudioBridge.kt after UsbManager has granted permission and
 * provided an open file descriptor for the USB DAC.
 *
 * ### Sequence
 *
 *  1. Allocate a UsbDriverContext on the heap.
 *  2. Call libusb_init() to create an isolated libusb session context.
 *     A dedicated (non-default) context is used so the driver does not
 *     interfere with any other libusb users in the process.
 *  3. Call libusb_set_option(ctx, LIBUSB_OPTION_NO_DEVICE_DISCOVERY) to
 *     suppress /dev/bus/usb enumeration, which would be blocked by SELinux
 *     on Android and produce a flood of LIBUSB_ERROR_ACCESS log spam.
 *  4. Call libusb_wrap_sys_device() to convert the Java-granted FD into a
 *     libusb_device_handle* without performing any additional USB enumeration.
 *  5. On any failure, tear down every resource acquired so far and return
 *     a negative error code so Kotlin can distinguish the failure phase.
 *  6. On success, return the heap pointer as a jlong handle; the Kotlin layer
 *     is responsible for passing it back to nativeRelease when done.
 *
 * ### Android FD ownership
 *
 *  The FD is opened and owned by UsbManager via UsbDeviceConnection on the
 *  Java side.  libusb_wrap_sys_device() uses it internally but does NOT take
 *  ownership; the FD must remain open for the lifetime of the driver and must
 *  be closed by the Java layer after libusb_close() has been called.
 *
 * @param env     JNI environment pointer.
 * @param clazz   UsbAudioBridge class reference (static native method).
 * @param fd      File descriptor from UsbManager.openDevice() / UsbDeviceConnection.getFileDescriptor().
 * @return        Heap pointer to UsbDriverContext cast to jlong on success,
 *                or a negative jlong error code (ERR_*) on failure.
 */
extern "C"
JNIEXPORT jlong JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_usb_UsbAudioBridge_nativeInitWithFileDescriptor(
        JNIEnv  *env,
        jclass   clazz,
        jint     fd)
{
    // Satisfy the compiler — clazz is unused in static native methods; the
    // parameter name is kept for ABI documentation purposes only.
    (void)env;
    (void)clazz;

    ULOGI("nativeInitWithFileDescriptor: entry — fd=%d", static_cast<int>(fd));

    // ── Phase 0: Validate the incoming FD ────────────────────────────────────
    // A negative FD means UsbManager failed to open the device on the Java
    // side before calling across the JNI boundary.  Reject it immediately to
    // produce a clear diagnostic instead of a cryptic libusb error.
    if (static_cast<int>(fd) < 0) {
        ULOGE("nativeInitWithFileDescriptor: invalid fd=%d — UsbManager.openDevice() failed on Java side",
              static_cast<int>(fd));
        return ERR_WRAP_SYS_DEVICE;
    }

    // ── Phase 1: Heap-allocate the driver context ─────────────────────────────
    // std::nothrow ensures we get nullptr instead of std::bad_alloc on OOM,
    // which is safer inside JNI where throwing across the boundary is undefined.
    auto *ctx = new (std::nothrow) UsbDriverContext();
    if (ctx == nullptr) {
        ULOGE("nativeInitWithFileDescriptor: out of memory allocating UsbDriverContext");
        return ERR_OUT_OF_MEMORY;
    }
    try {
        ctx->producer_coordinator = std::make_shared<UsbProducerCoordinator>();
    } catch (...) {
        ULOGE("nativeInitWithFileDescriptor: out of memory allocating producer coordinator");
        delete ctx;
        return ERR_OUT_OF_MEMORY;
    }
    ctx->device_fd = static_cast<int>(fd);

    // ── Phase 2 (pre-init): Set NO_DEVICE_DISCOVERY globally BEFORE libusb_init ──
    //
    // CRITICAL Android requirement (libusb 1.0.24+ Android backend):
    //
    // The Android libusb backend's init() function checks whether
    // LIBUSB_OPTION_NO_DEVICE_DISCOVERY has been set on the context.  If it has
    // NOT been set, the backend attempts to open and scan /dev/bus/usb, which is
    // blocked by SELinux for regular Android apps — the scan returns
    // LIBUSB_ERROR_IO, which propagates all the way back as the return value of
    // libusb_init() itself, producing the confusing "ERR_LIBUSB_INIT" failure.
    //
    // Setting the option on the NULL context installs it as a GLOBAL default
    // that is inherited by every subsequent libusb_init() call in the process.
    // This must be done BEFORE libusb_init(); setting it afterwards on the
    // per-context pointer (ctx->usb_ctx) is too late — the backend has already
    // tried the /dev/bus/usb scan during libusb_init().
    //
    // Reference: https://github.com/libusb/libusb/wiki/FAQ#Can_I_run_libusb_on_Android
    //            libusb commit b952d1f "android: support hotplug with no discovery"
    {
        const int pre_opt_ret = libusb_set_option(nullptr,
                                                  LIBUSB_OPTION_NO_DEVICE_DISCOVERY);
        if (pre_opt_ret != LIBUSB_SUCCESS) {
            // Non-fatal: some older libusb builds return LIBUSB_ERROR_NOT_SUPPORTED
            // for this when called with NULL context but still honour it per-context.
            // Log and continue — the per-context set in Phase 3 is the safety net.
            ULOGW("nativeInitWithFileDescriptor: global NO_DEVICE_DISCOVERY pre-set returned %s (%d) — "
                  "continuing; will retry on per-context in Phase 3",
                  libusb_error_name(pre_opt_ret), pre_opt_ret);
        } else {
            ULOGD("nativeInitWithFileDescriptor: global LIBUSB_OPTION_NO_DEVICE_DISCOVERY pre-set OK");
        }
    }

    // ── Phase 2: Initialise the libusb session context ────────────────────────
    // Passing &ctx->usb_ctx (non-null) creates an isolated context rather than
    // using the shared default context.  Isolation means:
    //   a) Our event loop can be shut down independently of other libusb users.
    //   b) Settings on this context do not affect other libusb users in-process.
    //
    // With the global NO_DEVICE_DISCOVERY option already set above, this call
    // no longer attempts /dev/bus/usb enumeration and succeeds on Android apps.
    const int init_ret = libusb_init(&ctx->usb_ctx);
    if (init_ret != LIBUSB_SUCCESS) {
        ULOGE("nativeInitWithFileDescriptor: libusb_init() failed — %s (%d) "
              "(hint: check that libusb1.0.so is loaded before audiophile_native; "
              "if error persists the global NO_DEVICE_DISCOVERY option may not be "
              "supported by this libusb build)",
              libusb_error_name(init_ret), init_ret);
        // usb_ctx is still null; teardown_context handles that safely.
        teardown_context(ctx);
        delete ctx;
        return ERR_LIBUSB_INIT;
    }
    ULOGI("nativeInitWithFileDescriptor: libusb context initialised OK (%p)", ctx->usb_ctx);

    // ── Phase 3: Disable /dev/bus/usb device discovery (per-context safety net) ──
    // Already set globally above, but also set on the per-context pointer to
    // guarantee correctness even on libusb builds that only honour per-context
    // options (pre-1.0.24 Android patches without global-option support).
    //
    // LIBUSB_OPTION_NO_DEVICE_DISCOVERY instructs the backend to skip the /dev
    // scan entirely, leaving libusb_wrap_sys_device() as the only device-open
    // path — which is exactly what this driver uses.
    const int opt_ret = libusb_set_option(ctx->usb_ctx,
                                          LIBUSB_OPTION_NO_DEVICE_DISCOVERY);
    if (opt_ret != LIBUSB_SUCCESS) {
        ULOGW("nativeInitWithFileDescriptor: per-context libusb_set_option(NO_DEVICE_DISCOVERY) "
              "returned %s (%d) — global pre-set should have covered this; continuing",
              libusb_error_name(opt_ret), opt_ret);
        // Non-fatal since global option was already set before libusb_init().
    } else {
        ULOGD("nativeInitWithFileDescriptor: per-context LIBUSB_OPTION_NO_DEVICE_DISCOVERY set OK");
    }

    // ── Phase 4: Wrap the Java-granted FD into a libusb device handle ─────────
    // libusb_wrap_sys_device() is the canonical Android path for obtaining a
    // device handle without requiring root or raw /dev/bus/usb access.
    //
    // The FD is cast to intptr_t as required by the libusb API.  On all
    // supported Android ABI targets (arm64-v8a, x86_64) sizeof(intptr_t) ==
    // sizeof(int64_t), so the cast is always lossless for a valid FD value.
    //
    // The FD is NOT transferred: libusb uses it internally but the Java-side
    // UsbDeviceConnection remains the owner and must keep it open.
    const int wrap_ret = libusb_wrap_sys_device(
            ctx->usb_ctx,
            static_cast<intptr_t>(ctx->device_fd),
            &ctx->device_handle);

    if (wrap_ret != LIBUSB_SUCCESS) {
        ULOGE("nativeInitWithFileDescriptor: libusb_wrap_sys_device() failed — %s (%d) fd=%d",
              libusb_error_name(wrap_ret), wrap_ret, ctx->device_fd);
        // device_handle is still null; teardown_context handles that safely.
        teardown_context(ctx);
        delete ctx;
        return ERR_WRAP_SYS_DEVICE;
    }

    // ── Phase 5: Success — return the opaque context pointer to Kotlin ─────────
    // The jlong handle is the raw heap pointer cast via reinterpret_cast.
    // The Kotlin layer treats it as an opaque token and must never interpret
    // or arithmetic-shift it; it is only passed back to native release/query calls.
    ULOGI("nativeInitWithFileDescriptor: SUCCESS — fd=%d handle=%p ctx_ptr=%p",
          ctx->device_fd,
          static_cast<void *>(ctx->device_handle),
          static_cast<void *>(ctx));

    return reinterpret_cast<jlong>(ctx);
}

// ─────────────────────────────────────────────────────────────────────────────
// UAC2 clock-source control
//
// Discovery (uac2_find_clock_source_id), candidate probing
// (uac2_force_clock_sample_rate), and the SET_CUR encoder all live in
// uac2_clock_control.cpp so every clock transfer in the driver shares one
// wire-format implementation.
// ─────────────────────────────────────────────────────────────────────────────

// ─────────────────────────────────────────────────────────────────────────────
// nativeSelectBestEndpointForFormat — format-aware UAC2 endpoint selection
// ─────────────────────────────────────────────────────────────────────────────

/**
 * nativeSelectBestEndpointForFormat — scan UAC2 descriptors and select the
 * isochronous OUT alternate setting whose `bSubslotSize` / `bBitResolution`
 * fields match the source audio's wire bit depth.
 *
 * Must be called AFTER nativeInitWithFileDescriptor() returns a valid handle,
 * because the libusb device handle is required to retrieve the active
 * configuration descriptor for parsing.
 *
 * ### Selection algorithm
 *
 * All candidate endpoints (UAC2 AudioStreaming, isochronous, OUT, non-zero MPS)
 * are enumerated by uac2_find_streaming_endpoints().  Each candidate is logged
 * with its parsed `bSubslotSize` and `bBitResolution` before any ranking.
 *
 * uac2_select_endpoint_for_format() then applies a four-pass cascade:
 *
 *   Pass 1 — bSubslotSize == target_subslot AND bBitResolution == effective_bit_depth
 *             AND effective_bytes_per_uframe >= required
 *   Pass 2 — bSubslotSize == target_subslot AND sufficient bandwidth
 *   Pass 3 — bSubslotSize >= target_subslot AND sufficient bandwidth   (overprovisioned)
 *   Pass 4 — any endpoint with sufficient bandwidth                    (last resort)
 *
 * target_subslot = effective_bit_depth / 8:
 *   16-bit → 2 bytes/subslot
 *   24-bit (after S32→S24 packing in the pump) → 3 bytes/subslot
 *   32-bit → 4 bytes/subslot
 *
 * ### Return value
 *
 * A 4-element jintArray packed as:
 *   [0] bInterfaceNumber
 *   [1] bAlternateSetting
 *   [2] bEndpointAddress
 *   [3] effective_bytes_per_uframe (the endpoint's bandwidth per 125 µs microframe)
 *
 * Returns null when:
 *   - The handle is invalid.
 *   - No UAC2 isochronous OUT endpoint is found.
 *   - No endpoint has sufficient bandwidth for the requested format.
 *
 * @param env               JNI environment.
 * @param clazz             UsbAudioBridge class (unused).
 * @param handle            jlong from nativeInitWithFileDescriptor.
 * @param sampleRateHz      Source sample rate in Hz (e.g. 44100, 192000).
 * @param effectiveBitDepth Wire bit depth after any packing: 16, 24, or 32.
 * @param channelCount      Audio channel count (e.g. 2 for stereo).
 * @param requireExactPcm   Require exact-width Type-I linear PCM with a valid resolution.
 * @return                  4-element jintArray on success, null on failure.
 */
extern "C"
JNIEXPORT jintArray JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_usb_UsbAudioBridge_nativeSelectBestEndpointForFormat(
        JNIEnv *env,
        jclass  clazz,
        jlong   handle,
        jint    sampleRateHz,
        jint    effectiveBitDepth,
        jint    channelCount,
        jboolean requireExactPcm)
{
    (void)clazz;

    ULOGI("nativeSelectBestEndpointForFormat: handle=%lld sampleRateHz=%d "
          "effectiveBitDepth=%d channelCount=%d requireExactPcm=%s",
          static_cast<long long>(handle),
          static_cast<int>(sampleRateHz),
          static_cast<int>(effectiveBitDepth),
          static_cast<int>(channelCount),
          requireExactPcm == JNI_TRUE ? "yes" : "no");

    if (!is_valid_handle(handle)) {
        ULOGE("nativeSelectBestEndpointForFormat: invalid handle — cannot scan descriptors");
        return nullptr;
    }

    auto *ctx = reinterpret_cast<UsbDriverContext *>(handle);
    if (ctx->device_handle == nullptr) {
        ULOGE("nativeSelectBestEndpointForFormat: device_handle is null inside context");
        return nullptr;
    }

    // ── Step A: scan all UAC2 isochronous OUT endpoints ──────────────────────
    // uac2_find_streaming_endpoints walks the active configuration descriptor
    // and parses every class-specific AS_GENERAL + FORMAT_TYPE_I blob it finds.
    // Results include bSubslotSize and bBitResolution for each alternate setting.
    const auto candidates = uac2_find_streaming_endpoints(ctx->device_handle);
    if (candidates.empty()) {
        ULOGE("nativeSelectBestEndpointForFormat: no UAC2 isochronous OUT endpoints found");
        return nullptr;
    }

    // ── Step B: format-aware selection ───────────────────────────────────────
    // Selects the alternate setting whose bSubslotSize matches effectiveBitDepth/8
    // so that the ISO packet width exactly matches the DAC's hardware parser expectations.
    // This prevents FSR ERROR caused by sending 16-bit frames to a 32-bit alt setting.
    const Uac2StreamingEndpointInfo *selected = uac2_select_endpoint_for_format(
            candidates,
            static_cast<int>(effectiveBitDepth),
            static_cast<int>(channelCount),
            static_cast<int>(sampleRateHz),
            requireExactPcm == JNI_TRUE);

    if (selected == nullptr) {
        ULOGE("nativeSelectBestEndpointForFormat: selection returned null — "
              "no matching endpoint for bitDepth=%d channels=%d sampleRate=%d",
              static_cast<int>(effectiveBitDepth),
              static_cast<int>(channelCount),
              static_cast<int>(sampleRateHz));
        return nullptr;
    }

    const uint8_t selected_valid_bits =
            selected->as_general.bit_resolution > 0U
                ? selected->as_general.bit_resolution
                : static_cast<uint8_t>(effectiveBitDepth);
    ctx->pcm_valid_bit_depth = selected_valid_bits;
    ctx->pcm_wire_format = UsbPcmWireFormat{
            static_cast<uint32_t>(sampleRateHz),
            static_cast<uint8_t>(channelCount),
            selected->as_general.subslot_size,
            selected_valid_bits,
    };
    if (!ctx->pcm_wire_format.is_valid()) {
        ULOGE("nativeSelectBestEndpointForFormat: unsupported PCM wire format "
              "subslot=%u validBits=%u channels=%u",
              static_cast<unsigned>(ctx->pcm_wire_format.subslot_bytes),
              static_cast<unsigned>(ctx->pcm_wire_format.valid_bits),
              static_cast<unsigned>(ctx->pcm_wire_format.channel_count));
        return nullptr;
    }
    (void)ctx->playback_state.transition_to(UsbPlaybackState::Configured);
    ULOGI("nativeSelectBestEndpointForFormat: PCM wire format %u valid bits in "
          "%u-byte subslot, %u channel(s)",
          static_cast<unsigned>(ctx->pcm_wire_format.valid_bits),
          static_cast<unsigned>(ctx->pcm_wire_format.subslot_bytes),
          static_cast<unsigned>(ctx->pcm_wire_format.channel_count));

    // ── Step C: pack selection + negotiated PCM shape for Kotlin ─────────────
    //
    // The transport factory needs both descriptor fields for honest telemetry:
    // bSubslotSize describes the USB carrier width, while bBitResolution is the
    // number of sample bits the selected alternate setting actually consumes.
    // Returning them with the endpoint identity prevents Kotlin from guessing
    // output precision from the decoder's (potentially pre-DSP) source format.
    jintArray result = env->NewIntArray(6);
    if (result == nullptr) {
        ULOGE("nativeSelectBestEndpointForFormat: NewIntArray(6) failed (OOM)");
        return nullptr;
    }

    jint elems[6] = {
        static_cast<jint>(selected->interface_number),
        static_cast<jint>(selected->alt_setting),
        static_cast<jint>(selected->endpoint_address),
        static_cast<jint>(selected->effective_bytes_per_uframe),
        static_cast<jint>(selected->as_general.subslot_size),
        static_cast<jint>(selected_valid_bits),
    };
    env->SetIntArrayRegion(result, 0, 6, elems);

    ULOGI("nativeSelectBestEndpointForFormat: → iface=%d alt=%d ep=0x%02X "
          "bpuf=%d subslot=%d validBits=%d",
          elems[0], elems[1], elems[2], elems[3], elems[4], elems[5]);

    return result;
}

// ─────────────────────────────────────────────────────────────────────────────
// nativeClaimInterface — claim the AudioStreaming interface (alt setting deferred)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * nativeClaimInterface — execute the three-phase device-control sequence.
 *
 * Must be called by UsbAudioBridge.kt AFTER:
 *   a) nativeInitWithFileDescriptor() returned a handle that passes is_valid_handle().
 *      On ARM64 MTE/HWASan devices valid handles may be negative — use is_valid_handle(),
 *      never `handle > 0`.
 *   b) The Kotlin layer ran the UAC2 descriptor scanner and selected
 *      the best endpoint (uac2_select_best_endpoint equivalent on Kotlin side).
 *
 * The three parameters mirror the fields of Uac2StreamingEndpointInfo that
 * were selected by the descriptor scanner and serialised across the JNI boundary.
 *
 * Return value contract (mirrors UsbControlStatus):
 *   0  = UsbControlStatus::Success
 *   1  = AutoDetachNotSupported  (non-fatal; claim succeeded)   ← never returned alone
 *   3  = ClaimBusyKernelDriverOwner  → Kotlin MUST fall back to AudioFlinger
 *   4  = ClaimDeviceDisconnected
 *   5  = ClaimInterfaceNotFound
 *   6  = ClaimAccessDenied
 *   7  = ClaimUnexpectedError
 *   8  = AltSettingTimeout
 *   9  = AltSettingStalled
 *   10 = AltSettingDeviceDisconnected
 *   11 = AltSettingUnexpectedError
 *   -4 = ERR_OUT_OF_MEMORY (handle invalid)
 *
 * On success (return == 0) the UsbDriverContext fields claimed_interface_number
 * and active_alt_setting are populated.  teardown_context() will release the
 * interface automatically on nativeRelease().
 *
 * @param env              JNI environment.
 * @param clazz            UsbAudioBridge class (unused).
 * @param handle           jlong from nativeInitWithFileDescriptor.
 * @param interfaceNumber  bInterfaceNumber from the descriptor scanner.
 * @param altSetting       bAlternateSetting from the descriptor scanner (must be ≥ 1).
 * @param endpointAddress  bEndpointAddress from the descriptor scanner (for logging).
 * @param effectiveBytesPerUframe  Scanner-computed bandwidth (for logging).
 * @return                 jint status code from UsbControlStatus enum.
 */
extern "C"
JNIEXPORT jint JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_usb_UsbAudioBridge_nativeClaimInterface(
        JNIEnv  *env,
        jclass   clazz,
        jlong    handle,
        jint     interfaceNumber,
        jint     altSetting,
        jint     endpointAddress,
        jint     effectiveBytesPerUframe)
{
    (void)env;
    (void)clazz;

    if (!is_valid_handle(handle)) {
        ULOGE("nativeClaimInterface: invalid handle=%lld — "
              "is_valid_handle() check failed (ARM64 MTE error code or null)",
              static_cast<long long>(handle));
        return static_cast<jint>(ERR_OUT_OF_MEMORY);   // reuse as "bad handle" sentinel
    }

    auto *ctx = reinterpret_cast<UsbDriverContext *>(handle);

    // Reconstruct a minimal Uac2StreamingEndpointInfo from the JNI parameters.
    // Only the fields consumed by uac2_claim_and_activate_interface() need to
    // be populated; the class-specific AS_GENERAL data is not needed here.
    Uac2StreamingEndpointInfo ep;
    ep.interface_number           = static_cast<uint8_t>(interfaceNumber);
    ep.alt_setting                = static_cast<uint8_t>(altSetting);
    ep.endpoint_address           = static_cast<uint8_t>(endpointAddress);
    ep.effective_bytes_per_uframe = static_cast<uint16_t>(effectiveBytesPerUframe);

    ULOGI("nativeClaimInterface: iface=%d alt=%d ep=0x%02X effective=%u bytes/µframe",
          interfaceNumber, altSetting, endpointAddress, effectiveBytesPerUframe);

    // Use the Phase 1+2-only variant so the alternate setting is NOT activated
    // yet.  The correct order required by FiiO KA series (and XMOS/Savitech
    // chips in general) is:
    //
    //   nativeClaimInterface()           ← claim streaming iface (NO alt-setting)
    //   nativeSetUac2ClockSampleRate()   ← claim ctrl iface 0 + SET_CUR clock
    //   nativeActivateAltSetting()       ← libusb_set_interface_alt_setting
    //   nativeAllocateTransferPool()
    //   nativeAttachUsbEngine()          ← ring pre-fill + submit transfers
    //
    // Activating the alt setting BEFORE the clock control transfer causes the
    // DAC's PLL to start at its firmware-default rate, which can differ from the
    // stream rate; on some firmware the DAC ignores a subsequent clock update
    // once it has begun streaming (manifests as persistent "FSR ERROR").
    const UsbInterfaceClaimResult claim = uac2_claim_interface_only(
            ctx->device_handle, ep);

    if (claim.success) {
        // Store the streaming interface number for teardown.
        // active_alt_setting is NOT set here — it will be set by
        // nativeActivateAltSetting() after the clock control transfer.
        ctx->claimed_interface_number = claim.claimed_interface_number;
        ULOGI("nativeClaimInterface: SUCCESS — streaming iface=%d claimed "
              "(alt-setting activation deferred until after clock setup)",
              ctx->claimed_interface_number);
    } else {
        ULOGE("nativeClaimInterface: FAILED — status=%d libusb=%d",
              static_cast<int>(claim.status), claim.libusb_error_code);
    }

    return static_cast<jint>(claim.status);
}

// ─────────────────────────────────────────────────────────────────────────────
// nativeSetUac2ClockSampleRate — program DAC clock to the stream sample rate
// ─────────────────────────────────────────────────────────────────────────────

/**
 * nativeSetUac2ClockSampleRate — program the UAC2 clock source frequency.
 *
 * Must be called AFTER nativeClaimInterface() returns 0 (the device handle is
 * in a claimed, alt-setting-active state) and BEFORE nativeAllocateTransferPool().
 *
 * ### Why this call is required
 *
 * A UAC2 alternate-setting selection allocates isochronous bus bandwidth but
 * does NOT program the DAC's internal sample-rate oscillator.  The oscillator
 * frequency is a separate UAC2 Clock Source entity attribute.  If the attribute
 * is not set the DAC's PLL holds whatever rate it last used (power-on default
 * or firmware preference), which may differ from the stream — causing an
 * audible artefact or the "FSR ERROR" / hardware mute seen on the FiiO KA5.
 *
 * ### Clock Source ID auto-detection
 *
 * If [clockSourceId] is ≤ 0, this function first calls uac2_find_clock_source_id()
 * which scans the active config descriptor for a CS_INTERFACE / CLOCK_SOURCE
 * (0x24 / 0x0A) descriptor.  If that also fails, the conventional UAC2 fallback
 * ID 1 is used (correct for the FiiO KA5 and most XMOS/ESS-based DACs).
 *
 * @param env                       JNI environment (unused).
 * @param clazz                     UsbAudioBridge class (unused).
 * @param handle                    Context handle from nativeInitWithFileDescriptor.
 * @param control_interface_number  bInterfaceNumber of the Audio Control interface (usually 0).
 * @param clock_source_id           bClockID of the Clock Source entity, or ≤ 0 for auto-detect.
 * @param sample_rate_hz            Target sample rate in Hz (e.g., 192000).
 * @return                          ≥ 0 on success; libusb negative error code on failure.
 */
extern "C"
JNIEXPORT jint JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_usb_UsbAudioBridge_nativeSetUac2ClockSampleRate(
        JNIEnv  *env,
        jclass   clazz,
        jlong    handle,
        jint     control_interface_number,
        jint     clock_source_id,
        jint     sample_rate_hz)
{
    (void)env;
    (void)clazz;

    if (!is_valid_handle(handle)) {
        ULOGE("nativeSetUac2ClockSampleRate: invalid handle=%lld",
              static_cast<long long>(handle));
        return -1;
    }

    auto *ctx = reinterpret_cast<UsbDriverContext *>(handle);
    if (ctx->device_handle == nullptr) {
        ULOGE("nativeSetUac2ClockSampleRate: device_handle is null — "
              "nativeInitWithFileDescriptor() may have failed");
        return -2;
    }

    // ── Clock Source ID resolution ────────────────────────────────────────────
    uint8_t csid = (clock_source_id > 0)
        ? static_cast<uint8_t>(clock_source_id)
        : 0u;

    if (csid == 0u) {
        // Attempt auto-detection from the config descriptor.
        const int detected = uac2_find_clock_source_id(ctx->device_handle);
        if (detected > 0) {
            csid = static_cast<uint8_t>(detected);
            ULOGI("nativeSetUac2ClockSampleRate: auto-detected bClockID=%u", csid);
        } else {
            // Fallback: UAC2 reference designs (XMOS, ESS ESS9038) and the FiiO
            // KA5 production firmware all use bClockID=1 as their single clock
            // source.  Hardcoding 1 is therefore correct for the vast majority of
            // consumer UAC2 DACs when descriptor parsing fails.
            csid = 1u;
            ULOGW("nativeSetUac2ClockSampleRate: auto-detect failed — "
                  "using fallback bClockID=1 (correct for FiiO KA5 and most "
                  "UAC2 DACs; adjust if the DAC still shows FSR ERROR)");
        }
    }

    ULOGI("nativeSetUac2ClockSampleRate: handing off to clock-ID probing — "
          "parsed csid=%u  rate=%u Hz  ctrl_iface=%d",
          static_cast<unsigned>(csid),
          static_cast<unsigned>(sample_rate_hz),
          static_cast<int>(control_interface_number));

    // ── Claim Audio Control interface before the control transfer ─────────────
    //
    // Android's libusb backend requires the process to have explicitly claimed
    // an interface before it can send class-specific control requests targeting
    // that interface in wIndex.  The Audio Control interface is separate from
    // the AudioStreaming interface claimed in nativeClaimInterface().
    //
    // Without this claim, libusb_control_transfer returns LIBUSB_ERROR_PIPE
    // because the kernel rejects the request — the host enforces exclusive USB
    // interface ownership and won't route a control transfer to an interface
    // owned by nobody (or by the kernel UAC2 driver).
    //
    // Failure is logged but non-fatal: some Android kernels auto-claim interface
    // 0 on behalf of the app, or the control transfer may still succeed on
    // lenient firmware.  We proceed regardless so the probing loop can
    // determine empirically whether the transfer works.
    {
        const int ctrl_iface = (control_interface_number >= 0)
            ? static_cast<int>(control_interface_number)
            : 0;

        const int ctrl_claim_ret = libusb_claim_interface(ctx->device_handle, ctrl_iface);
        if (ctrl_claim_ret == LIBUSB_SUCCESS) {
            ULOGI("nativeSetUac2ClockSampleRate: ctrl interface %d claimed OK — "
                  "control transfers to this interface are now enabled",
                  ctrl_iface);
            ctx->ctrl_interface_number = ctrl_iface;   // saved for teardown
        } else if (ctrl_claim_ret == LIBUSB_ERROR_BUSY) {
            // Interface 0 is often auto-claimed by the kernel UAC2 driver.
            // On Android this means the kernel holds a soft reference; the
            // control transfer may still succeed because EP0 is shared.
            ULOGW("nativeSetUac2ClockSampleRate: ctrl interface %d BUSY "
                  "(kernel may own it — control transfer may still succeed "
                  "via EP0 which is always accessible)",
                  ctrl_iface);
        } else {
            ULOGW("nativeSetUac2ClockSampleRate: libusb_claim_interface(ctrl=%d) "
                  "returned %s (%d) — continuing; transfer may still succeed",
                  ctrl_iface, libusb_error_name(ctrl_claim_ret), ctrl_claim_ret);
        }
    }

    // Use the candidate-probing helper rather than the single-shot call.
    // uac2_force_clock_sample_rate() always uses control interface 0; if the
    // caller explicitly provided a non-zero control_interface_number we honour
    // it through the single-shot path as a last resort after probing fails.
    //
    // winning_clock_id captures which bClockID the DAC actually ACK'd so we can
    // persist it in the context.  During DSD teardown the soft-reset sends the
    // 44100 Hz baseline to this exact ID — sending to any other ID triggers
    // LIBUSB_ERROR_PIPE (-9), which stalls the control endpoint and causes the
    // subsequent PCM session's clock programming to fail silently.
    int winning_clock_id = 0;
    const int brute_result = uac2_force_clock_sample_rate(
            ctx->device_handle,
            static_cast<int>(csid),
            static_cast<uint32_t>(sample_rate_hz),
            &winning_clock_id);

    if (brute_result == 4) {
        // Persist the winning clock source ID for use in Phase 6.5 DSD teardown.
        ctx->resolved_clock_source_id = static_cast<uint8_t>(winning_clock_id);
        ULOGI("nativeSetUac2ClockSampleRate: resolved_clock_source_id=%u "
              "stored in context (used by DSD soft-reset Phase 6.5 to avoid"
              " LIBUSB_ERROR_PIPE on teardown clock reset)",
              static_cast<unsigned>(ctx->resolved_clock_source_id));
        return brute_result;   // success — one of the candidates ACK'd
    }

    // Candidate probing exhausted every standard ID.  If the caller specified
    // an explicit non-zero control interface, make one last attempt with the
    // exact (csid, ctrl_iface) pair they requested — covers exotic multi-
    // function devices where the Audio Control interface is not 0.
    if (control_interface_number != 0) {
        ULOGW("nativeSetUac2ClockSampleRate: clock probing failed — "
              "retrying with explicit ctrl_iface=%d as last resort",
              static_cast<int>(control_interface_number));
        const int last_resort = uac2_set_clock_sample_rate(
                ctx->device_handle,
                static_cast<uint8_t>(control_interface_number),
                csid,
                static_cast<uint32_t>(sample_rate_hz));
        if (last_resort < 0) {
            ULOGE("nativeSetUac2ClockSampleRate: explicit ctrl_iface=%d attempt "
                  "failed too: %s (%d)",
                  static_cast<int>(control_interface_number),
                  libusb_error_name(last_resort), last_resort);
        } else {
            ctx->resolved_clock_source_id = csid;
        }
        return last_resort;
    }

    return brute_result;   // -1 from uac2_force_clock_sample_rate
}

// ─────────────────────────────────────────────────────────────────────────────
// nativeActivateAltSetting — activate the ISO alt setting AFTER clock setup
// ─────────────────────────────────────────────────────────────────────────────

/**
 * nativeActivateAltSetting — issue SET_INTERFACE to activate the USB
 * isochronous alternate setting on the streaming interface.
 *
 * ### Hard-reset strategy ("Alt 0 → Alt N" transition)
 *
 * Before jumping to the target `altSetting`, the native implementation first
 * drives the interface to Alt 0 (zero-bandwidth idle) via a preliminary
 * `SET_INTERFACE iface=N alt=0`.  This **"0→N" transition** is required by
 * many XMOS/FiiO/Savitech firmware variants to clear the DAC's internal ISO
 * DMA buffer from any stale state left by a prior session.  Without it, the
 * DAC endpoint remains logically "busy" from the kernel's perspective and all
 * subsequent `libusb_submit_transfer` calls return `LIBUSB_ERROR_BUSY (-6)`.
 *
 * The hard-reset return value is treated as non-fatal: if the interface is
 * already at Alt 0 or the firmware ignores the redundant request, the
 * warning is logged but execution continues.
 *
 * ### Call order (CRITICAL for FiiO KA series and XMOS/Savitech chips)
 *
 * ```
 * nativeClaimInterface()           — claims streaming interface (no alt yet)
 * nativeSetUac2ClockSampleRate()   — claims ctrl iface 0 + SET_CUR clock
 * nativeActivateAltSetting()       — THIS FUNCTION: Alt0→AltN hard-reset then ISO
 * nativeAllocateTransferPool()
 * nativeAttachUsbEngine()          — ring pre-fill → submit_all_transfers()
 * ```
 *
 * Sending SET_INTERFACE BEFORE the UAC2 clock SET_CUR causes the DAC's PLL
 * to lock to its firmware-default rate.  Some FiiO firmware ignores subsequent
 * clock updates once the ISO endpoint is active, permanently showing "FSR ERROR".
 * By activating the alt setting AFTER the clock transfer the DAC programmes its
 * PLL at the exact desired rate before allocating ISO bandwidth.
 *
 * @param env              JNI environment (unused).
 * @param clazz            UsbAudioBridge class (unused).
 * @param handle           Context handle from nativeInitWithFileDescriptor.
 * @param interfaceNumber  bInterfaceNumber of the streaming interface.
 * @param altSetting       bAlternateSetting to activate (must be ≥ 1).
 * @return                 0 (UsbControlStatus::Success) on success;
 *                         positive UsbControlStatus error code on failure.
 */
extern "C"
JNIEXPORT jint JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_usb_UsbAudioBridge_nativeActivateAltSetting(
        JNIEnv  *env,
        jclass   clazz,
        jlong    handle,
        jint     interfaceNumber,
        jint     altSetting,
        jint     endpointAddress)
{
    (void)env;
    (void)clazz;

    if (!is_valid_handle(handle)) {
        ULOGE("nativeActivateAltSetting: invalid handle=%lld",
              static_cast<long long>(handle));
        return static_cast<jint>(ERR_OUT_OF_MEMORY);
    }

    auto *ctx = reinterpret_cast<UsbDriverContext *>(handle);
    if (ctx->device_handle == nullptr) {
        ULOGE("nativeActivateAltSetting: device_handle is null");
        return static_cast<jint>(UsbControlStatus::AltSettingUnexpectedError);
    }

    ULOGI("nativeActivateAltSetting: SET_INTERFACE iface=%d alt=%d ep=0x%02X ...",
          static_cast<int>(interfaceNumber),
          static_cast<int>(altSetting),
          static_cast<int>(endpointAddress));

    const UsbControlStatus status = uac2_apply_alt_setting(
            ctx->device_handle,
            static_cast<int>(interfaceNumber),
            static_cast<int>(altSetting),
            static_cast<int>(endpointAddress));

    if (status == UsbControlStatus::Success) {
        // Record the now-active alt setting for diagnostic logging during teardown.
        ctx->active_alt_setting = static_cast<int>(altSetting);
        ULOGI("nativeActivateAltSetting: SUCCESS — iface=%d alt=%d ep=0x%02X ISO endpoint active",
              static_cast<int>(interfaceNumber),
              static_cast<int>(altSetting),
              static_cast<int>(endpointAddress));
    } else {
        ULOGE("nativeActivateAltSetting: FAILED — status=%d "
              "(iface=%d alt=%d ep=0x%02X); caller should release the streaming interface",
              static_cast<int>(status),
              static_cast<int>(interfaceNumber),
              static_cast<int>(altSetting),
              static_cast<int>(endpointAddress));
    }

    return static_cast<jint>(status);
}

// ─────────────────────────────────────────────────────────────────────────────
// nativeAllocateTransferPool — allocate the isochronous transfer ring
// ─────────────────────────────────────────────────────────────────────────────

/**
 * nativeAllocateTransferPool — pre-allocate the libusb isochronous transfer pool.
 *
 * Must be called by UsbAudioBridge.kt AFTER nativeClaimInterface() returns 0
 * (UsbControlStatus::Success).  Calling before a successful claim is an error —
 * the libusb_transfer structs embed the device_handle which must be in a
 * claimed + alt-setting-active state before transfers can be submitted.
 *
 * The pool is stored inside UsbDriverContext and destroyed automatically by
 * nativeRelease() via teardown_context().
 *
 * @param sampleRateHz       Audio sample rate in Hz (e.g., 192000).
 * @param bytesPerAudioFrame Bytes per interleaved audio frame (e.g., 8 for stereo 32-bit).
 * @param endpointAddress    Isochronous OUT endpoint address from the scanner.
 * @param poolSize           Number of transfer slots to pre-allocate (e.g., 16).
 * @param packetsPerTransfer ISO packets per transfer (e.g., 8 = 1 ms per transfer).
 * @return                   0 on success; negative on failure.
 */
extern "C"
JNIEXPORT jint JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_usb_UsbAudioBridge_nativeAllocateTransferPool(
        JNIEnv  *env,
        jclass   clazz,
        jlong    handle,
        jint     sampleRateHz,
        jint     bytesPerAudioFrame,
        jint     endpointAddress,
        jint     poolSize,
        jint     packetsPerTransfer)
{
    (void)env;
    (void)clazz;

    if (!is_valid_handle(handle)) {
        ULOGE("nativeAllocateTransferPool: invalid handle=%lld — "
              "is_valid_handle() check failed",
              static_cast<long long>(handle));
        return -1;
    }

    auto *ctx = reinterpret_cast<UsbDriverContext *>(handle);

    if (ctx->transfer_pool) {
        ULOGW("nativeAllocateTransferPool: pool already allocated — destroying old pool first");
        ctx->transfer_pool.reset();
    }

    // ── Endpoint direction validation ─────────────────────────────────────────
    // USB OUT endpoints have bit 7 clear (0x01–0x0F).
    // USB IN  endpoints have bit 7 set  (0x81–0x8F).
    // The DSD isochronous stream flows HOST → DEVICE, so we require an OUT
    // endpoint.  Log a warning if the caller passed an IN endpoint address by
    // mistake (e.g., a descriptor parser matched the wrong direction).
    // For the FiiO KA5 the DSD ISO OUT endpoint is expected at 0x01.
    {
        const uint8_t ep_byte = static_cast<uint8_t>(endpointAddress);
        if (ep_byte & 0x80u) {
            ULOGW("nativeAllocateTransferPool: endpoint 0x%02X has bit 7 SET — "
                  "this is a USB IN endpoint; expected an OUT endpoint (bit 7 clear). "
                  "FiiO KA5 DSD ISO OUT = 0x01.  Check the descriptor scanner.",
                  static_cast<unsigned>(ep_byte));
        } else {
            ULOGI("nativeAllocateTransferPool: endpoint 0x%02X — "
                  "valid USB OUT direction (bit 7 clear) ✓",
                  static_cast<unsigned>(ep_byte));
        }
    }

    IsoTransferPoolConfig cfg;
    cfg.sample_rate_hz        = static_cast<uint32_t>(sampleRateHz);
    cfg.bytes_per_audio_frame = static_cast<uint32_t>(bytesPerAudioFrame);
    cfg.endpoint_address      = static_cast<uint8_t>(endpointAddress);
    cfg.pool_size             = static_cast<uint32_t>(poolSize);
    cfg.packets_per_transfer  = static_cast<uint32_t>(packetsPerTransfer);

    std::string error_msg;
    ctx->transfer_pool = IsoTransferPool::create(ctx->device_handle, cfg, &error_msg);

    if (!ctx->transfer_pool) {
        ULOGE("nativeAllocateTransferPool: IsoTransferPool::create failed — %s",
              error_msg.c_str());
        return -2;
    }

    // Mirror the endpoint address into the context so teardown_context() can
    // pass it to libusb_clear_halt() in the DSD soft-reset phase (Phase 6.5),
    // which runs after the transfer pool has been destroyed in Phase 6.
    ctx->iso_endpoint_address = cfg.endpoint_address;

    ULOGI("nativeAllocateTransferPool: SUCCESS — "
          "%u slots × %u packets × %u B/µframe = %u B total buffer",
          ctx->transfer_pool->config().pool_size,
          ctx->transfer_pool->config().packets_per_transfer,
          ctx->transfer_pool->bytes_per_uframe(),
          ctx->transfer_pool->total_buffer_bytes());

    return 0;
}

// ─────────────────────────────────────────────────────────────────────────────
// nativeStartPlayback — prepare ring buffer + event thread (no submission).
// ─────────────────────────────────────────────────────────────────────────────

/**
 * nativeStartPlayback — prepare the PCM isochronous transport.
 *
 * Must be called by UsbAudioBridge.kt AFTER nativeAllocateTransferPool() returns 0.
 * Creates the SPSC ring buffer, attaches it to the transfer pool, and starts
 * the dedicated libusb event thread. Transfer submission is
 * intentionally deferred until a producer has placed real PCM in the ring.
 *
 * ### Startup sequence
 *
 *  1. Create SpscRingBuffer (128 KB — 43 ms of audio at 192 kHz / 32-bit stereo).
 *  2. Attach the ring buffer pointer to the IsoTransferPool so iso_transfer_callback
 *     can pop audio bytes on each transfer completion.
 *  3. Create LibusbEventThread — the thread starts immediately and begins
 *     calling libusb_handle_events_timeout_completed() in its loop.
 *  4. Return without submitting transfers. `nativeAttachUsbEngine()` submits
 *     after the native decoder pump pre-fills the ring; the enhanced Kotlin
 *     writer submits after its first real-audio ring push.
 *  5. On any failure, clean up all newly created resources and leave the pool
 *     allocated (nativeRelease() will free it) but not streaming.
 *
 * @param env     JNI environment pointer.
 * @param clazz   UsbAudioBridge class (unused).
 * @param handle  jlong from nativeInitWithFileDescriptor.
 * @return        0 on success; negative jint error code on failure.
 */
extern "C"
JNIEXPORT jint JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_usb_UsbAudioBridge_nativeStartPlayback(
        JNIEnv  *env,
        jclass   clazz,
        jlong    handle)
{
    (void)env;
    (void)clazz;

    if (!is_valid_handle(handle)) {
        ULOGE("nativeStartPlayback: invalid handle=%lld — is_valid_handle() check failed",
              static_cast<long long>(handle));
        return -1;
    }

    auto *ctx = reinterpret_cast<UsbDriverContext *>(handle);

    // Ring buffer + event thread bring-up shared with nativeStartDsdPlayback.
    const int transport_rc = usb_transport_prepare(ctx, "nativeStartPlayback");
    if (transport_rc != 0) return transport_rc;

    // ── ISO transfers are submitted later, in nativeAttachUsbEngine ─────────
    //
    // CRITICAL ORDERING CONSTRAINT — DO NOT SUBMIT TRANSFERS HERE.
    //
    // Transfers must NOT be submitted until the SPSC ring buffer has been
    // pre-filled with real decoded audio by the pump thread.  The pump thread
    // is started by nativeAttachUsbEngine(), which is called AFTER this function
    // returns.  If transfers are submitted now (while the ring is empty), every
    // callback will hit the underrun path and output silence frames.
    //
    // Many UAC2 DACs (including the FiiO KA5) respond to sustained silence in
    // an active streaming alt setting with LIBUSB_TRANSFER_STALL.  The callback's
    // Triage::Shutdown path then sets slot->shutdown = true and permanently stops
    // re-submission — the callback chain dies and the ring fills forever
    // (the "ring_free=0 KB, full_sleeps=N" deadlock fingerprint).
    //
    // The correct sequence is:
    //   nativeStartPlayback   → ring created, pool attached, event thread running
    //                           NO transfer submission here ← THIS STEP
    //   nativeAttachUsbEngine → pump starts, ring pre-fills to ~50%, THEN
    //                           IsoTransferPool::submit_all_transfers() is called
    //   nativeWriteToRingBuffer → enhanced PCM is converted/pushed, THEN the
    //                           transfer pool is submitted on its first write
    //
    // See engine_swap_bridge.cpp nativeAttachUsbEngine for the pre-fill wait
    // and the submit_all_transfers() call that actually starts the ISO pipeline.

    ULOGI("nativeStartPlayback: SUCCESS — ring buffer created, event thread running; "
          "ISO transfers await a native-pump or enhanced-writer real-audio prefill");
    return 0;
}

/**
 * nativeRelease — release the USB driver and free all associated resources.
 *
 * Must be called by UsbAudioBridge.kt after:
 *   a) All in-flight isochronous transfers have been cancelled and their
 *      completion callbacks have fired.
 *   b) The libusb event loop has been stopped.
 *   c) Any claimed interfaces have been released.
 *   d) any active producer handle has been detached.
 *
 * After this call the handle is invalid; passing it to any other native
 * function is undefined behaviour.
 *
 * Passing a zero handle or a known small-negative error sentinel
 * (ERR_LIBUSB_INIT … ERR_OUT_OF_MEMORY, i.e. -1 … -4) is a safe no-op.
 * On ARM64 MTE/HWASan devices valid handles may appear negative — they are
 * NOT treated as no-ops; use is_valid_handle() to distinguish them from
 * error sentinels.
 *
 * @param env     JNI environment pointer (unused; kept for ABI completeness).
 * @param clazz   UsbAudioBridge class reference (unused).
 * @param handle  jlong handle returned by nativeInitWithFileDescriptor.
 */
extern "C"
JNIEXPORT void JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_usb_UsbAudioBridge_nativeRelease(
        JNIEnv  *env,
        jclass   clazz,
        jlong    handle)
{
    (void)env;
    (void)clazz;

    if (!is_valid_handle(handle)) {
        // 0 means init never succeeded; -1..-4 are known error sentinels —
        // nothing to release.  ARM64 MTE/HWASan valid handles may be negative
        // but are caught by is_valid_handle() as non-null, non-sentinel values.
        ULOGD("nativeRelease: handle=%lld — no-op (invalid or uninitialized handle)",
              static_cast<long long>(handle));
        return;
    }

    auto *ctx = reinterpret_cast<UsbDriverContext *>(handle);
    ULOGI("nativeRelease: releasing driver context — fd=%d device_handle=%p",
          ctx->device_fd,
          static_cast<void *>(ctx->device_handle));

    // The aggregate enforces its own destruction order even when Kotlin did
    // not call nativeReleaseDsdManager explicitly.
    ctx->dsd_manager.reset();
    teardown_context(ctx);
    delete ctx;

    ULOGI("nativeRelease: all libusb resources freed");
}

// ─────────────────────────────────────────────────────────────────────────────
// nativeStartDsdPlayback — DSD session with automatic DoP fallback
// ─────────────────────────────────────────────────────────────────────────────

/**
 * nativeStartDsdPlayback — start a DSD isochronous stream with automatic
 * Native DSD → DoP fallback.
 *
 * Extends `nativeStartPlayback` with DSD-specific logic:
 *
 *   1. Creates the SPSC ring buffer (identical to nativeStartPlayback).
 *   2. Attaches the ring buffer to the transfer pool.
 *   3. Creates the libusb event thread.
 *   4. Creates a `DsdPlaybackManager` only when Native and DoP targets coexist.
 *   5. Selects Native DSD or direct DoP framing before producer attachment.
 *   6. Submits all ISO transfer slots.  If any submission fails,
 *      `notify_submit_failure()` is called on the manager so it will
 *      immediately fall back to DoP on its first monitor cycle.
 *   7. Engine attach arms observation only after the real producer starts.
 *
 * ### onEngineModeChanged callback contract
 *
 * The Kotlin `listener` object must implement:
 *   ```kotlin
 *   fun onEngineModeChanged(mode: Int)
 *   ```
 *   where 0 = NativeDsd (initial), 1 = DoP (after fallback).
 *
 * The callback is NOT guaranteed to be called when Native DSD remains stable
 * after the 200 ms window.  It is called exactly once if fallback occurs.
 *
 * ### Destruction order (CRITICAL)
 *
 * `nativeRelease(handle)` joins the monitor before transport teardown.
 * Kotlin may call `nativeReleaseDsdManager(handle)` earlier as an optimisation.
 *
 * @param env                    JNI environment pointer.
 * @param clazz                  UsbAudioBridge class (unused).
 * @param handle                 jlong from nativeInitWithFileDescriptor.
 * @param nativeDsdInterface     bInterfaceNumber of the Native DSD alt setting.
 * @param nativeDsdAltSetting    bAlternateSetting of the Native DSD iso endpoint.
 * @param pcmInterface           bInterfaceNumber of the PCM/DoP alt setting.
 * @param pcmAltSetting          bAlternateSetting of the PCM/DoP iso endpoint.
 * @param pcmEndpoint            bEndpointAddress of the PCM/DoP ISO OUT endpoint.
 * @param pcmBandwidth           Maximum bytes per USB microframe for that endpoint.
 * @param startInDop             true when the device has no Native DSD endpoint.
 * @param supportsDopFallback    true only when a distinct, validated DoP target exists.
 * @param useLsbf                true = use LSBF byte ordering (rare legacy DACs);
 *                               false = MSBF (standard for modern DACs).
 * @param listener               Kotlin object implementing onEngineModeChanged(int).
 * @return                       0 on success; negative jint error code on failure.
 */
extern "C"
JNIEXPORT jint JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_usb_UsbAudioBridge_nativeStartDsdPlayback(
        JNIEnv  *env,
        jclass   clazz,
        jlong    handle,
        jint     nativeDsdInterface,
        jint     nativeDsdAltSetting,
        jint     pcmInterface,
        jint     pcmAltSetting,
        jint     pcmEndpoint,
        jint     pcmBandwidth,
        jboolean startInDop,
        jboolean supportsDopFallback,
        jboolean useLsbf,
        jobject  listener)
{
    (void)clazz;

    if (!is_valid_handle(handle)) {
        ULOGE("nativeStartDsdPlayback: invalid handle=%lld — is_valid_handle() check failed",
              static_cast<long long>(handle));
        return -1;
    }

    auto *ctx = reinterpret_cast<UsbDriverContext *>(handle);
    (void)ctx->playback_state.transition_to(UsbPlaybackState::Configured);

    // Ring buffer + event thread bring-up shared with nativeStartPlayback.
    const int transport_rc = usb_transport_prepare(ctx, "nativeStartDsdPlayback");
    if (transport_rc != 0) return transport_rc;

    // ── Build DSD capability summary from JNI parameters ─────────────────────
    // The Kotlin layer already ran the capability scanner; we reconstruct a
    // minimal summary from the alt setting indices it selected.
    Uac2DsdCapabilitySummary capability;
    capability.supports_native_dsd    = startInDop == JNI_FALSE;
    capability.native_dsd_interface   = static_cast<uint8_t>(nativeDsdInterface);
    capability.native_dsd_alt_setting = static_cast<uint8_t>(nativeDsdAltSetting);
    capability.native_dsd_endpoint    = ctx->transfer_pool->config().endpoint_address;
    capability.supports_dop           = supportsDopFallback == JNI_TRUE;
    capability.pcm_interface          = static_cast<uint8_t>(pcmInterface);
    capability.pcm_alt_setting        = static_cast<uint8_t>(pcmAltSetting);
    capability.pcm_endpoint           = static_cast<uint8_t>(pcmEndpoint);
    capability.pcm_bandwidth          = static_cast<uint16_t>(
            std::max(0, std::min(static_cast<int>(
                                     std::numeric_limits<uint16_t>::max()),
                                 static_cast<int>(pcmBandwidth))));

    // ── Step E: Configure dsd_formatter for Native DSD ────────────────────────
    // Select MSBF (standard) or LSBF (legacy) based on caller's flag.
    // The formatter stored in ctx is used by the FFmpeg bridge (producer)
    // to determine whether to apply bit-reversal before pushing DSD bytes.
    ctx->dsd_wire_mode = startInDop == JNI_TRUE
            ? DsdWireMode::Dop
            : DsdWireMode::Native;
    ctx->dsd_formatter = make_dsd_frame_formatter(
            /*use_native_dsd=*/startInDop == JNI_FALSE,
            useLsbf ? NativeDsdBitOrder::Lsbf : NativeDsdBitOrder::Msbf);
    ULOGI("nativeStartDsdPlayback: transport=%s%s",
          startInDop == JNI_TRUE ? "DoP" : "Native DSD ",
          startInDop == JNI_TRUE ? "" : (useLsbf ? "LSBF" : "MSBF"));

    // ── Create DsdPlaybackManager ─────────────────────────────────────────────
    if (startInDop == JNI_FALSE && supportsDopFallback == JNI_TRUE) {
        JavaVM *vm = nullptr;
        if (env->GetJavaVM(&vm) != JNI_OK || !vm) {
            ULOGE("nativeStartDsdPlayback: GetJavaVM failed — "
                  "DsdPlaybackManager cannot be created");
            usb_transport_abort(ctx);
            return -5;
        }

        const jclass listener_class = env->GetObjectClass(listener);
        if (!listener_class) {
            ULOGE("nativeStartDsdPlayback: GetObjectClass(listener) failed");
            usb_transport_abort(ctx);
            return -6;
        }

        const jmethodID on_mode_changed_id =
            env->GetMethodID(listener_class, "onEngineModeChanged", "(I)V");
        env->DeleteLocalRef(listener_class);

        if (!on_mode_changed_id) {
            ULOGE("nativeStartDsdPlayback: GetMethodID(onEngineModeChanged) failed — "
                  "listener must declare 'fun onEngineModeChanged(mode: Int)'");
            usb_transport_abort(ctx);
            return -7;
        }

        ctx->dsd_manager = DsdPlaybackManager::create(
                ctx, capability, vm, listener, on_mode_changed_id);

        if (!ctx->dsd_manager) {
            ULOGE("nativeStartDsdPlayback: DsdPlaybackManager::create failed (OOM)");
            usb_transport_abort(ctx);
            return -8;
        }
    }

    prefill_dsd_silence(ctx->ring_buffer.get(), ctx->dsd_wire_mode);

    // ── Step G0: Cold-boot the ISO streaming interface ────────────────────────
    //
    // WHY HERE — not in nativeAttachUsbEngine
    // ──────────────────────────────────────────
    // nativeStartDsdPlayback is called BEFORE any ISO transfers are submitted;
    // at this point in the code the transfer pool has been allocated and configured
    // (nativeAllocateTransferPool) but NO libusb_submit_transfer() has
    // been called yet.  That means the Alt0 → AltN round-trip below cannot
    // interfere with in-flight transfers — there are none.
    //
    // Moving the cold boot to nativeAttachUsbEngine (as previously implemented)
    // was wrong because nativeStartDsdPlayback's Step G (below) already submits
    // all 16 silence slots to keep the DAC clock alive.  Any SET_INTERFACE after
    // that submit loop operates on live in-flight ISO transfers, causing the USB
    // host controller to abort them with LIBUSB_ERROR_IO (-1).
    //
    // WHAT THIS FIXES
    // ────────────────
    // On XMOS / Savitech firmware (FiiO KA5 and similar), the ISO endpoint DMA
    // engine can lock in a BUSY state between nativeActivateAltSetting() (issued
    // called ~50 ms before this point) and the first libusb_submit_transfer().
    // A full Alt0 → 20 ms → AltN round-trip resets the DMA engine so the very
    // first ISO submission succeeds without LIBUSB_ERROR_BUSY (-6).
    //
    // SEQUENCE
    // ─────────
    //   1. Alt0  — quiesce endpoint, release USB bus bandwidth, reset DMA FIFO
    //   2. 20 ms — XMOS PLL idle window (PLL already locked from the SET_CUR;
    //              the Alt0 round-trip does NOT reprogram the clock)
    //   3. AltN  — re-arm streaming endpoint; DAC firmware re-launches DMA
    //   4. libusb_clear_halt — clear any software ENDPOINT_HALT that may have
    //              accumulated during the Alt0 / AltN transition
    if (ctx->claimed_interface_number >= 0 && ctx->active_alt_setting > 0) {
        const int iface = ctx->claimed_interface_number;
        const int alt_n = ctx->active_alt_setting;

        ULOGI("nativeStartDsdPlayback: [cold-boot] iface=%d  Alt%d → Alt0 → Alt%d  "
              "(resetting DMA engine before first ISO submit)",
              iface, alt_n, alt_n);

        // Step G0-1: quiesce — deactivate ISO endpoint.
        const int alt0_ret =
            libusb_set_interface_alt_setting(ctx->device_handle, iface, 0);
        if (alt0_ret == LIBUSB_SUCCESS) {
            ULOGD("nativeStartDsdPlayback: [cold-boot] Alt0 set ✓");
        } else {
            ULOGW("nativeStartDsdPlayback: [cold-boot] Alt0 failed: %s (%d) — "
                  "proceeding; endpoint DMA may still be locked",
                  libusb_error_name(alt0_ret), alt0_ret);
        }

        // Step G0-2: idle window — XMOS PLL and DMA FIFO drain.
        {
            struct timespec req = { 0, 20'000'000L };  // 20 ms
            nanosleep(&req, nullptr);
        }

        // Step G0-3: re-arm — restore streaming alt-setting.
        const int alt_n_ret =
            libusb_set_interface_alt_setting(ctx->device_handle, iface, alt_n);
        if (alt_n_ret == LIBUSB_SUCCESS) {
            ULOGI("nativeStartDsdPlayback: [cold-boot] Alt%d re-armed ✓ "
                  "— DAC DMA engine reset; ready for first ISO submit",
                  alt_n);
        } else {
            ULOGW("nativeStartDsdPlayback: [cold-boot] Alt%d re-arm failed: %s (%d) — "
                  "proceeding to clear_halt; first submit may encounter BUSY",
                  alt_n, libusb_error_name(alt_n_ret), alt_n_ret);
        }

        // Step G0-4: clear_halt — remove any software ENDPOINT_HALT that may have
        // been set during the Alt0 / AltN transition on strict USB controllers.
        // LIBUSB_ERROR_NOT_FOUND means the endpoint was not halted — the normal path.
        const uint8_t ep_addr =
            static_cast<uint8_t>(ctx->transfer_pool->config().endpoint_address);
        const int halt_ret = libusb_clear_halt(ctx->device_handle, ep_addr);
        if (halt_ret == LIBUSB_SUCCESS) {
            ULOGI("nativeStartDsdPlayback: [cold-boot] clear_halt(ep=0x%02X) ✓",
                  static_cast<unsigned>(ep_addr));
        } else if (halt_ret == LIBUSB_ERROR_NOT_FOUND) {
            ULOGD("nativeStartDsdPlayback: [cold-boot] ep=0x%02X not halted — no clear needed",
                  static_cast<unsigned>(ep_addr));
        } else {
            ULOGW("nativeStartDsdPlayback: [cold-boot] clear_halt(ep=0x%02X) returned %s (%d)",
                  static_cast<unsigned>(ep_addr), libusb_error_name(halt_ret), halt_ret);
        }
    } else {
        ULOGD("nativeStartDsdPlayback: [cold-boot] skipped — "
              "claimed_interface_number=%d  active_alt_setting=%d",
              ctx->claimed_interface_number, ctx->active_alt_setting);
    }

    // ── Step G: Submit all transfer slots ─────────────────────────────────────
    const auto  &transfers      = ctx->transfer_pool->transfers();
    auto        &slots          = ctx->transfer_pool->slots();
    bool         any_fail       = false;
    uint32_t     submitted      = 0;

    for (std::size_t i = 0; i < transfers.size(); ++i) {
        slots[i].in_flight.store(true, std::memory_order_release);

        const int rc = libusb_submit_transfer(transfers[i]);
        if (rc != LIBUSB_SUCCESS) {
            ULOGE("nativeStartDsdPlayback: libusb_submit_transfer slot[%zu] failed: "
                  "%s (%d)", i, libusb_error_name(rc), rc);
            slots[i].in_flight.store(false, std::memory_order_release);
            slots[i].shutdown.store(true, std::memory_order_release);
            any_fail = true;
        } else {
            ++submitted;
        }
    }

    if (any_fail) {
        if (ctx->dsd_manager) {
            ULOGE("nativeStartDsdPlayback: %zu/%zu slot(s) submitted — "
                  "partial failure will trigger DoP fallback",
                  static_cast<std::size_t>(submitted), transfers.size());
            ctx->dsd_manager->notify_submit_failure();
        } else {
            ULOGE("nativeStartDsdPlayback: %zu/%zu slot(s) submitted and no "
                  "fallback transport is available",
                  static_cast<std::size_t>(submitted), transfers.size());
            (void)ctx->playback_state.transition_to(UsbPlaybackState::Failed);
            return -9;
        }
    }

    // Stall observation is armed only after nativeAttachUsbEngine has attached
    // and started the real DSD producer. Starting it here would spend most of
    // the 200 ms window observing intentional startup silence.

    // Latch the DSD session flag so teardown_context() Phase 6.5 knows to send
    // the XMOS Soft Reset sequence (Alt-0 + clear_halt + 44100 Hz clock + 50 ms)
    // before releasing the streaming interface.  This prevents the XMOS chip's
    // DSD DSP lock from persisting into the next PCM session ("FSR ERROR").
    ctx->was_dsd_session = true;

    ULOGI("nativeStartDsdPlayback: SUCCESS — %u transfer(s) in flight, "
          "observation window armed (200 ms), event thread running",
          submitted);
    return 0;
}

// ─────────────────────────────────────────────────────────────────────────────
// nativeReleaseDsdManager — join the DSD monitor thread before teardown
// ─────────────────────────────────────────────────────────────────────────────

/**
 * nativeReleaseDsdManager — destroy the DSD playback manager safely.
 *
 * The manager's destructor joins the monitor thread. `nativeRelease()` performs
 * the same reset automatically before transport teardown, so this explicit
 * entry point is optional and useful only for ending observation earlier.
 *
 * Calling this when no DSD manager exists (PCM-only session) is a safe no-op.
 *
 * @param env     JNI environment pointer (unused).
 * @param clazz   UsbAudioBridge class (unused).
 * @param handle  jlong handle from nativeInitWithFileDescriptor.
 */
extern "C"
JNIEXPORT void JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_usb_UsbAudioBridge_nativeReleaseDsdManager(
        JNIEnv  *env,
        jclass   clazz,
        jlong    handle)
{
    (void)env;
    (void)clazz;

    if (!is_valid_handle(handle)) {
        ULOGD("nativeReleaseDsdManager: handle=%lld — no-op (invalid handle)",
              static_cast<long long>(handle));
        return;
    }

    auto *ctx = reinterpret_cast<UsbDriverContext *>(handle);

    if (ctx->dsd_manager) {
        ULOGI("nativeReleaseDsdManager: destroying DsdPlaybackManager "
              "(joining monitor thread)");
        ctx->dsd_manager.reset();   // joins monitor
    }
}
