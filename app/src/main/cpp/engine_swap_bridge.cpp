// ─────────────────────────────────────────────────────────────────────────────
// engine_swap_bridge.cpp
//
// JNI bridge for USB hot-plug handling (ACTION_USB_DEVICE_ATTACHED).
//
// This file provides the complete native half of the engine swap performed when
// Android broadcasts ACTION_USB_DEVICE_ATTACHED for an audio DAC.
//
// ── Legacy → New engine transition ───────────────────────────────────────────
//
//   BEFORE (legacy):
//     FFmpeg → nativeReadNextBuffer → Kotlin DirectByteBuffer → AudioTrack
//
//   AFTER (new, this file's domain):
//     FFmpeg → IAudioDecoder  → DecoderToRingBridge → SpscRingBuffer → libusb
//
// ── JNI surface exposed by this file ─────────────────────────────────────────
//
//   nativeAttachUsbEngine(driverHandle, decoderHandle, onEofListener, initialVolume)
//     → jlong (bridge handle, or negative SWAP_ERR_* on failure)
//
//     Called by the Kotlin UsbEngineSwapCoordinator after:
//       1. UsbManager has granted permission and opened the device FD.
//       2. nativeInitWithFileDescriptor() has succeeded (driverHandle is valid).
//       3. nativeClaimInterface() + nativeAllocateTransferPool() have succeeded.
//       4. nativeStartPlayback() / nativeStartDsdPlayback() has prepared the
//          ring, transfer pool, and libusb event thread. DSD may already have
//          submitted its clock-priming transfers.
//       5. The Kotlin legacy AudioTrack write loop has been stopped.
//
//     This function ONLY:
//       a. Validates driverHandle and decoderHandle.
//       b. Creates a DecoderToRingBridge wiring decoder output → ring buffer.
//       c. Registers an optional JNI EOF callback.
//       d. Sets the initial software volume scalar.
//       e. Starts the bridge pump thread so the ring begins filling.
//       f. Waits (up to 2 s) for the ring to reach 50% capacity.
//       g. Submits PCM transfers after pre-fill, or leaves already-running DSD
//          transfers untouched.
//
//   nativeDetachUsbEngine(bridgeHandle)
//     → void
//
//     Called by the Kotlin coordinator on ACTION_USB_DEVICE_DETACHED or when
//     the user switches back to AudioFlinger output.  Stops the pump thread
//     and destroys the bridge, leaving the decoder and ring intact for the
//     caller to clean up.
//
//   nativePumpBridgeSeek(bridgeHandle, decoderHandle, positionUs)
//     → jboolean
//
//     Routes a transport seek command through the bridge to the underlying
//     decoder.  The bridge's pump thread is paused briefly during seek so the
//     spill buffer is coherent at the new position before the pump resumes.
//
//   nativeBridgeIsEof(bridgeHandle)
//     → jboolean
//
//     Polls the bridge's eof_ flag.  The Kotlin layer calls this on each
//     playback timer tick to detect end-of-track without blocking.
//
// ── Thread model ──────────────────────────────────────────────────────────────
//
//   Calling thread (JNI calls):       Kotlin audio-init / coordinator thread
//   Pump thread (bridge internal):    C++ std::thread, started by start()
//   ISO callback thread:              libusb event thread (already running when
//                                     nativeAttachUsbEngine is called)
//   SPSC invariant:                   pump = single producer; ISO = single consumer
// ─────────────────────────────────────────────────────────────────────────────

#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <array>
#include <chrono>        // steady_clock — pre-fill timeout
#include <cmath>
#include <cstdint>
#include <cstring>
#include <memory>
#include <new>
#include <string>
#include <unistd.h>      // usleep — pre-fill poll interval

#include "audio_gain.h"              // shared quadratic volume taper
#include "usb_driver_context.h"
#include "usb_handle_validation.h"   // shared jlong handle validation (MTE-safe)
#include "decoder_to_ring_bridge.h"
#include "i_audio_decoder.h"
#include "ffmpeg_audio_decoder.h"
#include "dsd_playback_manager.h"
#include "jni_global_ref.h"          // RAII global-ref ownership for callbacks
#include "libusb/libusb.h"

// ── Logging ───────────────────────────────────────────────────────────────────

static constexpr const char *ESB_TAG = "EngineSwapBridge";

#define ESBLOGD(...) __android_log_print(ANDROID_LOG_DEBUG, ESB_TAG, __VA_ARGS__)
#define ESBLOGI(...) __android_log_print(ANDROID_LOG_INFO,  ESB_TAG, __VA_ARGS__)
#define ESBLOGW(...) __android_log_print(ANDROID_LOG_WARN,  ESB_TAG, __VA_ARGS__)
#define ESBLOGE(...) __android_log_print(ANDROID_LOG_ERROR, ESB_TAG, __VA_ARGS__)

// ── JNI return codes (mirror UsbEngineSwapStatus in Kotlin) ───────────────────

/// Swap succeeded; bridgeHandle is valid.
[[maybe_unused]] static constexpr jint SWAP_OK      =  0;
/// driverHandle is 0 or negative — USB init has not succeeded.
static constexpr jint SWAP_ERR_BAD_DRIVER_HANDLE    = -1;
/// decoderHandle is 0 — no active decoder to bridge from.
static constexpr jint SWAP_ERR_BAD_DECODER_HANDLE   = -2;
/// UsbDriverContext::ring_buffer is null — call nativeStartPlayback first.
static constexpr jint SWAP_ERR_RING_NOT_READY       = -3;
/// Out of memory allocating the DecoderToRingBridge.
static constexpr jint SWAP_ERR_OOM                  = -4;
/// Pump thread failed to start.
[[maybe_unused]] static constexpr jint SWAP_ERR_PUMP_FAILED = -5;
/// libusb_submit_transfer() failed during the initial pre-filled submission.
static constexpr jint SWAP_ERR_SUBMIT_FAILED        = -6;

/// Direct-buffer argument is null, non-direct, undersized, or not float-aligned.
static constexpr jint RING_WRITE_ERR_BAD_BUFFER     = -101;
/// Driver context has no prepared PCM ring / transfer pool / event thread.
static constexpr jint RING_WRITE_ERR_NOT_READY      = -102;
/// Initial ISO transfer submission failed after the first real-audio prefill.
static constexpr jint RING_WRITE_ERR_SUBMIT_FAILED  = -103;
/// ISO callbacks stopped draining the ring for longer than the bounded timeout.
static constexpr jint RING_WRITE_ERR_TIMEOUT        = -104;

/// Maximum amount of converted PCM kept on the stack per ring-buffer push.
static constexpr std::size_t RING_WRITE_CHUNK_BYTES = 16U * 1024U;
/// Prevents a dead USB callback chain from blocking the engine HandlerThread forever.
static constexpr int RING_WRITE_TIMEOUT_MS = 2000;
/// Back-pressure polling interval, aligned with the existing native pump cadence.
static constexpr useconds_t RING_WRITE_POLL_US = 500U;

// Handle validation (including the ARM64 MTE / HWASan tagged-pointer contract)
// is shared with usb_audio_bridge.cpp via usb_handle_validation.h.
static_assert(SWAP_ERR_SUBMIT_FAILED == kEngineSwapLowestErrorSentinel,
              "update kEngineSwapLowestErrorSentinel in usb_handle_validation.h");

/// Returns true when @p h is safe to dereference as a UsbDriverContext*.
static inline bool is_valid_driver_handle(jlong h) noexcept {
    return is_valid_native_handle(static_cast<int64_t>(h),
                                  kUsbDriverLowestErrorSentinel);
}

/// Returns true when @p h is safe to dereference as a DecoderToRingBridge*.
static inline bool is_valid_bridge_handle(jlong h) noexcept {
    return is_valid_native_handle(static_cast<int64_t>(h),
                                  kEngineSwapLowestErrorSentinel);
}

// ─────────────────────────────────────────────────────────────────────────────
// nativeOpenDecoderForUsb
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Open an FfmpegAudioDecoder for a file path that will feed the USB engine.
 *
 * This replaces the legacy Kotlin call to `FFmpegDecoder.open()` for the USB
 * path.  The returned handle is a C++ pointer cast to jlong; the Kotlin layer
 * stores it opaquely and passes it to nativeAttachUsbEngine() and
 * nativeReleaseUsbDecoder().
 *
 * ### Stripping the legacy AudioTrack output
 *
 * The original code path was:
 *   Kotlin FFmpegDecoder.open() → sets up an AudioTrack → write loop runs
 *
 * The new code path for USB:
 *   nativeOpenDecoderForUsb() → FfmpegAudioDecoder (no AudioTrack, no JNI loop)
 *   nativeAttachUsbEngine()   → DecoderToRingBridge pump feeds the ring
 *
 * The existing Kotlin FFmpegDecoder.kt / AudioTrack write loop is NOT called
 * when a USB DAC is connected; this function's handle replaces it.
 *
 * @param env       JNI environment.
 * @param clazz     EngineSwapBridge class (static method).
 * @param jpath     Absolute path to the audio source file.
 * @param jforce_pcm When JNI_TRUE, DSD sources are decimated to 88 200 Hz PCM
 *                   (Tier-3 fallback).  Pass JNI_FALSE when the DAC supports
 *                   DoP / native DSD so the raw DSD bits reach the ring.
 * @return           jlong handle (FfmpegAudioDecoder*) on success; 0 on failure.
 */
extern "C"
JNIEXPORT jlong JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_usb_EngineSwapBridge_nativeOpenDecoderForUsb(
        JNIEnv  *env,
        jclass   /*clazz*/,
        jstring  jpath,
        jboolean jforce_pcm)
{
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    if (path == nullptr) {
        ESBLOGE("nativeOpenDecoderForUsb: GetStringUTFChars returned null");
        return 0L;
    }

    const bool force_pcm = (jforce_pcm == JNI_TRUE);

    std::string error_msg;
    auto decoder = FfmpegAudioDecoder::open(path, force_pcm, &error_msg);

    env->ReleaseStringUTFChars(jpath, path);

    if (!decoder) {
        ESBLOGE("nativeOpenDecoderForUsb: FfmpegAudioDecoder::open failed — %s",
                error_msg.c_str());
        return 0L;
    }

    // Transfer heap ownership to the Kotlin opaque handle.  nativeReleaseUsbDecoder
    // is responsible for calling delete via the matching release function.
    IAudioDecoder *raw = decoder.release();

    ESBLOGI("nativeOpenDecoderForUsb: decoder ready — %dHz %dch is_dsd=%s ptr=%p",
            raw->format().sample_rate_hz,
            raw->format().channel_count,
            raw->format().is_dsd ? "yes" : "no",
            static_cast<void *>(raw));

    return reinterpret_cast<jlong>(raw);
}

// ─────────────────────────────────────────────────────────────────────────────
// nativeReleaseUsbDecoder
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Destroy an FfmpegAudioDecoder opened via nativeOpenDecoderForUsb.
 *
 * Must be called AFTER nativeDetachUsbEngine() (pump thread stopped) to
 * guarantee no concurrent read_pcm() / read_dsd() calls are in-flight.
 *
 * @param env           JNI environment (unused).
 * @param clazz         EngineSwapBridge class (unused).
 * @param decoder_handle jlong from nativeOpenDecoderForUsb.
 */
extern "C"
JNIEXPORT void JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_usb_EngineSwapBridge_nativeReleaseUsbDecoder(
        JNIEnv  * /*env*/,
        jclass   /*clazz*/,
        jlong    decoder_handle)
{
    if (decoder_handle == 0L) {
        ESBLOGD("nativeReleaseUsbDecoder: handle=0 — no-op");
        return;
    }
    auto *decoder = reinterpret_cast<IAudioDecoder *>(decoder_handle);
    delete decoder;
    ESBLOGI("nativeReleaseUsbDecoder: decoder freed");
}

// ─────────────────────────────────────────────────────────────────────────────
// nativeAttachUsbEngine
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Wire the decoder to the libusb ring buffer and start the pump thread.
 *
 * This is the primary engine-swap function called by the Kotlin coordinator
 * immediately after ACTION_USB_DEVICE_ATTACHED is processed.  It:
 *
 *   1. Validates both handles.
 *   2. Retrieves the SpscRingBuffer from the UsbDriverContext.
 *   3. Creates a DecoderToRingBridge.
 *   4. Registers an EOF callback so the Kotlin layer is notified via the
 *      JavaVM when the track ends, without the pump thread needing JNI itself.
 *   5. Starts the pump thread — from this moment the legacy AudioTrack path
 *      must NOT also be active (it would be a second producer on the same
 *      decoder, violating the single-owner contract).
 *
 * @param env             JNI environment.
 * @param clazz           EngineSwapBridge class (unused; static method).
 * @param driver_handle   jlong from nativeInitWithFileDescriptor (UsbDriverContext*).
 * @param decoder_handle  jlong from nativeOpenDecoderForUsb (IAudioDecoder*).
 * @param on_eof_listener Kotlin object implementing onDecoderEof() for end-of-track
 *                        notification from the pump thread.  May be null if the
 *                        Kotlin layer polls nativeBridgeIsEof() instead.
 * @param initial_volume  Raw UI volume position in [0.0, 1.0] applied to the bridge
 *                        BEFORE the pump thread is started.  This guarantees the first
 *                        pre-fill chunks enter the ring at the correct amplitude — no
 *                        brief full-volume blast while the Kotlin layer races to deliver
 *                        a post-attach nativeSetVolume call.  Pass the value obtained
 *                        from UsbVolumeController.volumePct / 100f.
 * @return                jlong bridge handle on success; negative error code on failure.
 *                        Pass the bridge handle to nativeDetachUsbEngine() when done.
 */
extern "C"
JNIEXPORT jlong JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_usb_EngineSwapBridge_nativeAttachUsbEngine(
        JNIEnv  *env,
        jclass   /*clazz*/,
        jlong    driver_handle,
        jlong    decoder_handle,
        jobject  on_eof_listener,
        jfloat   initial_volume)
{
    // ── Validate handles ──────────────────────────────────────────────────────

    // ARM64 MTE/HWASan: valid heap pointers have top-byte set → appear negative
    // as signed jlong.  Use is_valid_driver_handle() instead of `<= 0LL`.
    if (!is_valid_driver_handle(driver_handle)) {
        ESBLOGE("nativeAttachUsbEngine: invalid driver_handle=%lld — "
                "call nativeInitWithFileDescriptor first (ARM64 MTE note: "
                "valid tagged pointers are accepted; only null and error codes "
                "-1..-4 are rejected)",
                static_cast<long long>(driver_handle));
        return static_cast<jlong>(SWAP_ERR_BAD_DRIVER_HANDLE);
    }
    if (decoder_handle == 0LL) {
        ESBLOGE("nativeAttachUsbEngine: invalid decoder_handle=0 — "
                "call nativeOpenDecoderForUsb first");
        return static_cast<jlong>(SWAP_ERR_BAD_DECODER_HANDLE);
    }

    auto *ctx     = reinterpret_cast<UsbDriverContext *>(driver_handle);
    auto *decoder = reinterpret_cast<IAudioDecoder *>(decoder_handle);

    // ── Verify the ring buffer is ready ───────────────────────────────────────
    // The ring is allocated by nativeStartPlayback() / nativeStartDsdPlayback().
    // On the USB attach path the order is:
    //   nativeInitWithFileDescriptor → nativeClaimInterface →
    //   nativeAllocateTransferPool   → nativeStartPlayback (ring + ISO) →
    //   nativeAttachUsbEngine (this)
    if (!ctx->ring_buffer) {
        ESBLOGE("nativeAttachUsbEngine: ring_buffer is null — "
                "call nativeStartPlayback before attaching the decoder");
        return static_cast<jlong>(SWAP_ERR_RING_NOT_READY);
    }

    // ── Create the bridge ─────────────────────────────────────────────────────
    auto bridge = DecoderToRingBridge::create(
            decoder,
            ctx->ring_buffer.get(),
            ctx->pcm_wire_format,
            ctx->producer_coordinator);
    if (!bridge) {
        ESBLOGE("nativeAttachUsbEngine: DecoderToRingBridge::create failed (OOM)");
        return static_cast<jlong>(SWAP_ERR_OOM);
    }
    if (ctx->was_dsd_session) {
        bridge->set_dsd_wire_mode(ctx->dsd_wire_mode);
    }
    if (!ctx->producer_coordinator ||
        !ctx->producer_coordinator->attach(bridge.get())) {
        ESBLOGE("nativeAttachUsbEngine: a producer is already attached");
        return static_cast<jlong>(SWAP_ERR_PUMP_FAILED);
    }

    // ── Register EOF callback via JNI global ref ───────────────────────────────
    // The pump thread is a plain C++ std::thread; it cannot hold a local JNI
    // reference.  We acquire the JavaVM pointer and a global reference to the
    // listener object, then build a lambda that attaches/detaches as a daemon
    // thread to deliver the single onDecoderEof() call.
    if (on_eof_listener != nullptr) {
        JavaVM *vm  = nullptr;
        if (env->GetJavaVM(&vm) == JNI_OK && vm) {
            // Obtain method ID while we still hold a valid JNIEnv*.
            jclass listener_class = env->GetObjectClass(on_eof_listener);
            jmethodID on_eof_id   = nullptr;
            if (listener_class) {
                on_eof_id = env->GetMethodID(listener_class, "onDecoderEof", "()V");
                env->DeleteLocalRef(listener_class);
            }

            if (on_eof_id) {
                // The global ref must be released when the callback is
                // DESTROYED, not when it fires: EOF is not guaranteed (the
                // user can skip the track), and tying the DeleteGlobalRef to
                // invocation leaked one reference per skipped track.  The
                // shared JniGlobalRef holder releases the ref from whatever
                // thread destroys the lambda (bridge teardown or replacement).
                jobject global_listener = env->NewGlobalRef(on_eof_listener);
                auto listener_ref =
                    std::make_shared<JniGlobalRef>(vm, global_listener);

                bridge->set_on_eof([vm, listener_ref, on_eof_id]() {
                    // The pump thread is a plain C++ std::thread; attach it as
                    // a daemon only if it is not already a JVM thread, and
                    // detach only what we attached here.
                    JNIEnv *callback_env = nullptr;
                    bool attached_here = false;
                    if (vm->GetEnv(reinterpret_cast<void **>(&callback_env),
                                   JNI_VERSION_1_6) != JNI_OK) {
                        if (vm->AttachCurrentThreadAsDaemon(&callback_env, nullptr)
                                != JNI_OK || callback_env == nullptr) {
                            ESBLOGW("on_eof_callback: AttachCurrentThreadAsDaemon "
                                    "failed — onDecoderEof not delivered");
                            return;
                        }
                        attached_here = true;
                    }

                    callback_env->CallVoidMethod(listener_ref->get(), on_eof_id);
                    if (callback_env->ExceptionCheck()) {
                        callback_env->ExceptionDescribe();
                        callback_env->ExceptionClear();
                    }

                    if (attached_here) {
                        vm->DetachCurrentThread();
                    }
                });

                ESBLOGD("nativeAttachUsbEngine: EOF JNI callback registered");
            } else {
                ESBLOGW("nativeAttachUsbEngine: GetMethodID(onDecoderEof) failed — "
                        "onDecoderEof() will not be called on EOF");
            }
        }
    }

    // ── Pre-flight volume: set BEFORE starting the pump thread ───────────────
    // Applying the initial volume here guarantees that every chunk the pump
    // pushes during the pre-fill window — and thereafter — is already at the
    // correct amplitude.  Without this, the pump would run at the mute-guard
    // default (0.0f) until the Kotlin post-attach nativeSetVolume call arrived,
    // producing a brief silence gap at the start of playback.
    //
    // The quadratic taper (position²) is applied inside bridge->set_volume() so
    // the call here satisfies both the "pre-flight" ordering requirement and the
    // "taper happens inside nativeSetVolume" requirement. This is the same
    // curve nativeWriteToRingBuffer applies for the enhanced (DSP) write path,
    // so switching between passthrough and enhanced sinks never changes the
    // perceived loudness at a given UI position.
    bridge->set_volume(static_cast<float>(initial_volume));
    ESBLOGI("nativeAttachUsbEngine: initial volume set — raw=%.3f before pump start",
            static_cast<float>(initial_volume));

    // ── Start the pump thread ─────────────────────────────────────────────────
    // From this point the legacy AudioTrack write-loop MUST NOT call
    // nativeReadNextBuffer() on the same decoder.  The Kotlin coordinator is
    // responsible for ensuring the legacy loop is stopped before calling here.
    if (!bridge->start()) {
        ESBLOGE("nativeAttachUsbEngine: failed to start decoder pump thread");
        return static_cast<jlong>(SWAP_ERR_PUMP_FAILED);
    }
    (void)ctx->playback_state.transition_to(UsbPlaybackState::Priming);

    // ── Pre-fill wait: block until ring reaches ≥ 50% capacity ───────────────
    //
    // The pump thread started above is the SOLE producer for the ring buffer.
    // We wait here (on the JNI coordinator thread) until the pump has pushed
    // enough decoded audio to guarantee that the very first ISO callback finds
    // real data in the ring instead of underrunning.
    //
    // ### Why we pre-fill BEFORE submitting ISO transfers (PCM path)
    //
    // nativeStartPlayback deliberately does NOT submit ISO transfers — it creates
    // the ring buffer and event thread but defers submission to this function.
    // Submitting with an empty ring causes the initial callbacks to output silence
    // frames.  On XMOS/FiiO firmware, sustained silence in an active ISO alt
    // setting triggers LIBUSB_TRANSFER_STALL → `Triage::Shutdown` in the callback
    // → the entire callback chain dies and the ring fills forever (the
    // "ring_free=0 KB, full_sleeps=N" deadlock fingerprint).
    //
    // nativeStartDsdPlayback submits its own transfers BEFORE calling this function
    // (it pre-fills with silence frames because DSD requires the clock to be live
    // before the decoder attaches).  For those sessions transfers are already in
    // flight when we reach this pre-fill block; we must NOT re-submit them.
    //
    // The submit_all_transfers() call below the wait block handles both cases by
    // checking the actual in_flight state of the pool slots rather than relying
    // on any boolean flag.
    //
    // Bounds:
    //   • Target  : ring_capacity / 2  (50 % = 64 KB for the default 128 KB ring)
    //   • Timeout : 2 000 ms            (generous for slow decoders / cold start)
    //   • Poll    : 2 ms                (low CPU, 1000× faster than timeout)
    //
    // If the timeout expires we log a warning and continue — an initial underrun
    // is preferable to blocking the coordinator thread indefinitely.
    {
        const std::size_t ring_cap     = ctx->ring_buffer->capacity();
        const std::size_t prefill_goal = ring_cap / 2;  // 50% = 64 KB for 128 KB ring

        constexpr int kTimeoutMs  = 2000;
        constexpr int kPollUs     = 2000;   // 2 ms

        const auto deadline = std::chrono::steady_clock::now() +
                              std::chrono::milliseconds(kTimeoutMs);

        ESBLOGI("nativeAttachUsbEngine: waiting for ring pre-fill "
                "(goal=%zu KB / %zu KB capacity) ...",
                prefill_goal / 1024UL, ring_cap / 1024UL);

        while (ctx->ring_buffer->size() < prefill_goal) {
            if (std::chrono::steady_clock::now() >= deadline) {
                ESBLOGW("nativeAttachUsbEngine: pre-fill timeout after %d ms — "
                        "ring at %zu KB / %zu KB; proceeding without full pre-fill "
                        "(expect brief initial underruns on first callbacks)",
                        kTimeoutMs,
                        ctx->ring_buffer->size() / 1024UL,
                        ring_cap / 1024UL);
                break;
            }
            usleep(static_cast<unsigned>(kPollUs));
        }

        ESBLOGI("nativeAttachUsbEngine: ring pre-fill done — "
                "%zu KB / %zu KB buffered; ready to submit ISO transfers",
                ctx->ring_buffer->size() / 1024UL,
                ring_cap / 1024UL);
    }

    // ── Submit ISO transfers (PCM) / verify in-flight (DSD) ──────────────────
    //
    // Session-type detection: inspect actual slot state rather than a boolean flag
    // so the decision is grounded in real hardware state.
    //
    //   DSD session  — nativeStartDsdPlayback already called libusb_submit_transfer
    //                  for all slots; in_flight == true on every slot.  Re-submitting
    //                  here would return LIBUSB_ERROR_BUSY (-6) for every slot and
    //                  corrupt the event-thread's in_flight accounting.  Skip.
    //
    //   PCM session  — nativeStartPlayback created the ring + event thread but
    //                  intentionally deferred submission to this point so that the
    //                  very first ISO callbacks find pre-buffered audio instead of
    //                  silence.  in_flight == false on every slot.  Submit now.
    {
        bool any_in_flight = false;
        const auto &pool_slots = ctx->transfer_pool->slots();
        for (const auto &slot : pool_slots) {
            if (slot.in_flight.load(std::memory_order_acquire)) {
                any_in_flight = true;
                break;
            }
        }

        if (any_in_flight) {
            // DSD path: nativeStartDsdPlayback already owns the ISO schedule.
            // The ring is now pre-filled so the very next callback resubmission
            // will pop real audio instead of the initial DSD silence frames.
            ESBLOGI("nativeAttachUsbEngine: ISO transfers already in-flight "
                    "(DSD session — submitted by nativeStartDsdPlayback); "
                    "skipping re-submission; ring now has real audio for callbacks");
        } else {
            // PCM path: this is the first and only submit call for this session.
            // The ring pre-fill above guarantees real audio is available for the
            // first callback — no STALL-inducing silence burst.
            ESBLOGI("nativeAttachUsbEngine: submitting all ISO transfers to libusb "
                    "(PCM session — nativeStartPlayback deferred submission here; "
                    "ring is pre-filled with %zu KB of decoded audio)",
                    ctx->ring_buffer->size() / 1024UL);

            const int submit_result = ctx->transfer_pool->submit_all_transfers();

            if (submit_result < 0) {
                // submit_all_transfers() returns -1 on the first libusb_submit_transfer
                // failure.  Any slots that DID succeed remain in-flight and must be
                // drained via teardown_context() — the Kotlin layer must call
                // nativeRelease() after receiving this error code.
                ESBLOGE("nativeAttachUsbEngine: submit_all_transfers FAILED "
                        "(code=%d) — ISO pipeline will not start; "
                        "Kotlin must call nativeRelease() to drain and cancel "
                        "any partially-submitted slots",
                        submit_result);
                // bridge is still held by unique_ptr here — its destructor will
                // stop the pump thread cleanly before the Kotlin teardown runs.
                return static_cast<jlong>(SWAP_ERR_SUBMIT_FAILED);
            }

            ESBLOGI("nativeAttachUsbEngine: ISO pipeline started — "
                    "all %d transfer(s) submitted to libusb; "
                    "ISO callbacks will begin firing at the DAC's 125 µs cadence",
                    submit_result);
        }
    }

    ESBLOGI("nativeAttachUsbEngine: SUCCESS — pump thread running, "
            "decoder wired to ring buffer (%zu KB), "
            "ISO pipeline started — all transfers submitted to libusb",
            ctx->ring_buffer->capacity() / 1024UL);

    if (ctx->dsd_manager) {
        (void)ctx->playback_state.transition_to(
                UsbPlaybackState::StreamingNativeDsd);
        if (!ctx->dsd_manager->arm_observation_window()) {
            ESBLOGE("nativeAttachUsbEngine: DSD observation thread failed to start");
            return static_cast<jlong>(SWAP_ERR_PUMP_FAILED);
        }
    } else if (ctx->was_dsd_session) {
        (void)ctx->playback_state.transition_to(
                ctx->dsd_wire_mode == DsdWireMode::Dop
                    ? UsbPlaybackState::StreamingDop
                    : UsbPlaybackState::StreamingNativeDsd);
    } else {
        (void)ctx->playback_state.transition_to(UsbPlaybackState::StreamingPcm);
    }

    // ── Return bridge handle ───────────────────────────────────────────────────
    return reinterpret_cast<jlong>(bridge.release());
}

// ─────────────────────────────────────────────────────────────────────────────
// nativeDetachUsbEngine
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Stop the pump thread and destroy the bridge.
 *
 * Called by the Kotlin coordinator on ACTION_USB_DEVICE_DETACHED, on user
 * request to switch back to AudioTrack output, or when the application exits.
 *
 * After this call:
 * - The ring buffer still holds buffered audio; the ISO callback drains it.
 * - The decoder is unaffected; nativeReleaseUsbDecoder() must be called
 *   separately when the track ends or after the ring is fully drained.
 *
 * Passing a zero or negative handle is a safe no-op.
 *
 * @param env             JNI environment (unused).
 * @param clazz           EngineSwapBridge class (unused).
 * @param bridge_handle   jlong from nativeAttachUsbEngine.
 */
extern "C"
JNIEXPORT void JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_usb_EngineSwapBridge_nativeDetachUsbEngine(
        JNIEnv  * /*env*/,
        jclass   /*clazz*/,
        jlong    bridge_handle)
{
    // ARM64 MTE/HWASan: bridge handles are heap pointers with top-byte set →
    // appear negative as signed jlong.  Only treat 0 (uninitialised sentinel)
    // as a no-op; valid tagged pointers must be dereferenced and destroyed.
    if (!is_valid_bridge_handle(bridge_handle)) {
        ESBLOGD("nativeDetachUsbEngine: handle=%lld — no-op (null or error sentinel)",
                static_cast<long long>(bridge_handle));
        return;
    }

    auto *bridge = reinterpret_cast<DecoderToRingBridge *>(bridge_handle);

    ESBLOGI("nativeDetachUsbEngine: stopping pump thread and destroying bridge");

    // stop() signals the pump thread and blocks until it exits.  Destroying the
    // bridge afterwards is safe because the destructor calls stop() again as a
    // guard, which is a documented no-op when already stopped.
    delete bridge;

    ESBLOGI("nativeDetachUsbEngine: bridge destroyed");
}

// ─────────────────────────────────────────────────────────────────────────────
// nativePumpBridgeSeek
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Seek the underlying decoder to `position_us` microseconds.
 *
 * The pump thread is stopped before the seek and restarted after.  This
 * prevents the pump from pushing pre-seek bytes into the ring while the codec
 * is mid-flush, which would cause an audible glitch.
 *
 * Ring buffer bytes already in the ring at seek time are NOT cleared — the ISO
 * callback will drain them before the post-seek audio arrives.  For seamless
 * seeking the ring should be cleared externally (via SpscRingBuffer::reset())
 * ONLY if the caller is certain no concurrent ISO callback pop() is active.
 * Since clearing the ring mid-stream is inherently racy, this function leaves
 * the ring intact and relies on the brief silence gap (kDefaultRingBufferBytes
 * / bytes-per-second ≈ 43 ms) as a natural transition boundary.
 *
 * @param env             JNI environment (unused).
 * @param clazz           EngineSwapBridge class (unused).
 * @param bridge_handle   jlong from nativeAttachUsbEngine.
 * @param position_us     Seek target in microseconds from stream start.
 * @return                JNI_TRUE on success; JNI_FALSE if the seek failed.
 */
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_usb_EngineSwapBridge_nativePumpBridgeSeek(
        JNIEnv  * /*env*/,
        jclass   /*clazz*/,
        jlong    bridge_handle,
        jlong    decoder_handle,
        jlong    position_us)
{
    if (!is_valid_bridge_handle(bridge_handle) || decoder_handle == 0LL) {
        ESBLOGE("nativePumpBridgeSeek: invalid handle(s) — bridge=%lld decoder=%lld "
                "(ARM64 MTE note: valid tagged pointers look negative as signed jlong)",
                static_cast<long long>(bridge_handle),
                static_cast<long long>(decoder_handle));
        return JNI_FALSE;
    }

    auto *bridge  = reinterpret_cast<DecoderToRingBridge *>(bridge_handle);
    auto *decoder = reinterpret_cast<IAudioDecoder *>(decoder_handle);

    ESBLOGD("nativePumpBridgeSeek: position_us=%lld — stopping pump for seek",
            static_cast<long long>(position_us));

    // Halt the pump thread so the spill buffer and codec state are stable
    // during av_seek_frame() / avcodec_flush_buffers().
    bridge->stop();

    const bool seek_ok = decoder->seek(static_cast<int64_t>(position_us));

    if (seek_ok) {
        // Resume pumping from the new position.
        if (!bridge->start()) {
            ESBLOGE("nativePumpBridgeSeek: seek succeeded but pump restart failed");
            return JNI_FALSE;
        }
        ESBLOGI("nativePumpBridgeSeek: seek to %lld µs succeeded — pump restarted",
                static_cast<long long>(position_us));
    } else {
        // Seek failed — the decoder cursor is undefined.  Do not restart the
        // pump; the Kotlin layer should close and re-open the decoder.
        ESBLOGE("nativePumpBridgeSeek: seek failed — pump not restarted, "
                "caller must re-open the decoder");
    }

    return seek_ok ? JNI_TRUE : JNI_FALSE;
}

// ─────────────────────────────────────────────────────────────────────────────
// nativeSeekUsbDecoder
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Seek a not-yet-attached USB decoder to `position_us` microseconds.
 *
 * Pre-attach counterpart of nativePumpBridgeSeek: the decoder has a
 * single-owner contract, so this must only be called while no pump thread is
 * attached (before nativeAttachUsbEngine, or after nativeDetachUsbEngine).
 *
 * Seeking BEFORE the attach matters: nativeAttachUsbEngine pre-fills the ring
 * from the decoder's current position and the ring is never cleared afterwards
 * (clearing races with the ISO consumer).  A pending seek applied only after
 * the attach therefore leaves start-of-track audio in the ring, which the DAC
 * audibly plays before jumping to the target position.
 *
 * @param decoder_handle  jlong from nativeOpenDecoderForUsb.
 * @param position_us     Seek target in microseconds from stream start.
 * @return                JNI_TRUE on success; JNI_FALSE on failure.
 */
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_usb_EngineSwapBridge_nativeSeekUsbDecoder(
        JNIEnv  * /*env*/,
        jclass   /*clazz*/,
        jlong    decoder_handle,
        jlong    position_us)
{
    if (decoder_handle == 0LL) {
        ESBLOGE("nativeSeekUsbDecoder: decoder_handle=0 — call nativeOpenDecoderForUsb first");
        return JNI_FALSE;
    }

    auto *decoder = reinterpret_cast<IAudioDecoder *>(decoder_handle);
    const bool seek_ok = decoder->seek(static_cast<int64_t>(position_us));

    if (seek_ok) {
        ESBLOGI("nativeSeekUsbDecoder: pre-attach seek to %lld µs succeeded",
                static_cast<long long>(position_us));
    } else {
        ESBLOGE("nativeSeekUsbDecoder: seek to %lld µs failed — "
                "caller should re-open the decoder",
                static_cast<long long>(position_us));
    }

    return seek_ok ? JNI_TRUE : JNI_FALSE;
}

// ─────────────────────────────────────────────────────────────────────────────
// nativeBridgeIsEof
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Poll whether the pump thread has reached end-of-stream.
 *
 * The Kotlin layer calls this on each playback timer tick as an alternative to
 * the onDecoderEof() JNI callback when a listener object is not desired.
 *
 * Thread-safe: reads an atomic flag written by the pump thread.
 *
 * @param env             JNI environment (unused).
 * @param clazz           EngineSwapBridge class (unused).
 * @param bridge_handle   jlong from nativeAttachUsbEngine.
 * @return                JNI_TRUE if EOF, JNI_FALSE otherwise.
 */
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_usb_EngineSwapBridge_nativeBridgeIsEof(
        JNIEnv  * /*env*/,
        jclass   /*clazz*/,
        jlong    bridge_handle)
{
    if (!is_valid_bridge_handle(bridge_handle)) return JNI_FALSE;
    const auto *bridge = reinterpret_cast<const DecoderToRingBridge *>(bridge_handle);
    return bridge->is_eof() ? JNI_TRUE : JNI_FALSE;
}

// ─────────────────────────────────────────────────────────────────────────────
// nativeWriteToRingBuffer — Kotlin DSP output → S32LE → libusb ring
// ─────────────────────────────────────────────────────────────────────────────

/** Returns whether at least one ISO transfer slot is currently owned by libusb. */
static bool has_in_flight_transfer(const IsoTransferPool &pool) noexcept
{
    for (const auto &slot : pool.slots()) {
        if (slot.in_flight.load(std::memory_order_acquire)) return true;
    }
    return false;
}

/**
 * Feeds float32 DSP output into the custom USB PCM transport.
 *
 * The enhancement graph intentionally produces interleaved IEEE float samples so
 * AudioTrack can consume the same stage on non-USB routes. UAC2 DAC PCM alternate
 * settings normally consume signed integer samples, however, so this boundary
 * converts every finite float in [-1, 1] to S32LE before it reaches the ring.
 * Non-finite values are replaced with digital silence and out-of-range values are
 * saturated, preventing NaN bit patterns or overshoots from reaching the DAC.
 *
 * `nativeStartPlayback()` deliberately leaves PCM transfers unsubmitted. On the
 * first successful ring push this function submits the pool, guaranteeing that
 * the first callback sees real audio instead of an empty-ring silence burst. This
 * is the Kotlin-writer counterpart of the native pump's prefill/submit sequence.
 */
extern "C"
JNIEXPORT jint JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_usb_EngineSwapBridge_nativeWriteToRingBuffer(
        JNIEnv *env,
        jclass /*clazz*/,
        jlong driver_handle,
        jobject input_buffer,
        jint byte_count,
        jfloat volume)
{
    if (!is_valid_driver_handle(driver_handle)) {
        ESBLOGE("nativeWriteToRingBuffer: invalid driver handle=%lld",
                static_cast<long long>(driver_handle));
        return RING_WRITE_ERR_NOT_READY;
    }
    if (input_buffer == nullptr || byte_count <= 0 ||
        (byte_count % static_cast<jint>(sizeof(float))) != 0) {
        ESBLOGE("nativeWriteToRingBuffer: invalid buffer/count count=%d", byte_count);
        return RING_WRITE_ERR_BAD_BUFFER;
    }

    auto *ctx = reinterpret_cast<UsbDriverContext *>(driver_handle);
    if (!ctx->ring_buffer || !ctx->transfer_pool || !ctx->event_thread) {
        ESBLOGE("nativeWriteToRingBuffer: PCM transport not prepared "
                "(ring=%p pool=%p event=%p)",
                static_cast<void *>(ctx->ring_buffer.get()),
                static_cast<void *>(ctx->transfer_pool.get()),
                static_cast<void *>(ctx->event_thread.get()));
        return RING_WRITE_ERR_NOT_READY;
    }

    const auto capacity = env->GetDirectBufferCapacity(input_buffer);
    const auto *input = static_cast<const uint8_t *>(
            env->GetDirectBufferAddress(input_buffer));
    if (input == nullptr || capacity < static_cast<jlong>(byte_count)) {
        ESBLOGE("nativeWriteToRingBuffer: input must be a direct buffer "
                "capacity=%lld requested=%d",
                static_cast<long long>(capacity), byte_count);
        return RING_WRITE_ERR_BAD_BUFFER;
    }

    // Shared quadratic taper — must match DecoderToRingBridge::set_volume() so
    // switching between the passthrough and enhanced sinks never changes the
    // perceived loudness at a given UI position.
    const double gain = ui_position_to_gain(static_cast<double>(volume));
    const PcmWireFormatter pcm_formatter(ctx->pcm_wire_format);
    std::array<uint8_t, RING_WRITE_CHUNK_BYTES> wire_chunk{};

    std::size_t input_offset = 0U;
    bool transfers_started = has_in_flight_transfer(*ctx->transfer_pool);
    auto backpressure_deadline = std::chrono::steady_clock::now() +
            std::chrono::milliseconds(RING_WRITE_TIMEOUT_MS);

    while (input_offset < static_cast<std::size_t>(byte_count)) {
        const std::size_t remaining = static_cast<std::size_t>(byte_count) - input_offset;
        const std::size_t chunk_bytes = std::min(remaining, wire_chunk.size());
        const auto formatted = pcm_formatter.format_float32(
                input + input_offset,
                chunk_bytes,
                gain,
                wire_chunk.data(),
                wire_chunk.size());
        if (!formatted.success || formatted.bytes_written != chunk_bytes) {
            ESBLOGE("nativeWriteToRingBuffer: PCM formatter rejected float chunk");
            return RING_WRITE_ERR_BAD_BUFFER;
        }

        std::size_t chunk_offset = 0U;
        while (chunk_offset < chunk_bytes) {
            const std::size_t free_bytes = ctx->ring_buffer->free_space();
            if (free_bytes == 0U) {
                if (std::chrono::steady_clock::now() >= backpressure_deadline) {
                    ESBLOGE("nativeWriteToRingBuffer: back-pressure timeout "
                            "ring=%zu/%zu bytes inFlight=%s",
                            ctx->ring_buffer->size(), ctx->ring_buffer->capacity(),
                            transfers_started ? "yes" : "no");
                    return RING_WRITE_ERR_TIMEOUT;
                }
                usleep(RING_WRITE_POLL_US);
                continue;
            }

            const std::size_t push_bytes = std::min(free_bytes, chunk_bytes - chunk_offset);
            if (!ctx->ring_buffer->push(wire_chunk.data() + chunk_offset, push_bytes)) {
                usleep(RING_WRITE_POLL_US);
                continue;
            }
            chunk_offset += push_bytes;
            backpressure_deadline = std::chrono::steady_clock::now() +
                    std::chrono::milliseconds(RING_WRITE_TIMEOUT_MS);

            if (!transfers_started) {
                const int submitted = ctx->transfer_pool->submit_all_transfers();
                if (submitted < 1) {
                    ESBLOGE("nativeWriteToRingBuffer: initial ISO submission failed "
                            "after %zu bytes of real-audio prefill", ctx->ring_buffer->size());
                    return RING_WRITE_ERR_SUBMIT_FAILED;
                }
                transfers_started = true;
                ESBLOGI("nativeWriteToRingBuffer: enhanced PCM ISO pipeline started "
                        "with %zu bytes prefilled across %d transfer slots",
                        ctx->ring_buffer->size(), submitted);
            }
        }

        input_offset += chunk_bytes;
    }

    return byte_count;
}

// ─────────────────────────────────────────────────────────────────────────────
// nativeSetVolume — software volume scalar for the libusb bypass path
// ─────────────────────────────────────────────────────────────────────────────

/**
 * nativeSetVolume — update the pump thread's software volume scalar.
 *
 * Because the libusb engine bypasses Android AudioFlinger entirely, the device
 * volume rocker has no effect on playback level.  This JNI entry point exposes
 * the bridge's atomic volume scalar so the Kotlin layer can implement a
 * responsive volume control without any round-trips through the OS audio stack.
 *
 * ### Thread-safety
 *
 * The atomic store uses relaxed ordering.  The pump thread reads the scalar at
 * the start of each chunk (~1 ms of audio) so volume changes take effect within
 * one chunk boundary — imperceptible for typical volume step sizes.
 *
 * ### Processing order (precision rationale)
 *
 * For S16LE sources the multiplication is performed at float32 precision
 * before the S16 source is widened into the negotiated 32-bit wire container.
 * Applying volume AFTER the shift would scale only bits 31:16 and leave the
 * lower 16 bits unchanged, producing quantisation artefacts proportional to
 * the shift amount.  The float-first approach preserves the full dynamic range
 * of the original 16-bit sample in the output 32-bit word.
 *
 * @param env           JNI environment (unused).
 * @param clazz         EngineSwapBridge class (unused).
 * @param bridge_handle jlong handle from nativeAttachUsbEngine.
 * @param volume        Linear amplitude scalar in [0.0, 1.0].  Values outside
 *                      this range are clamped inside set_volume().
 */
extern "C"
JNIEXPORT void JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_usb_EngineSwapBridge_nativeSetVolume(
        JNIEnv  * /*env*/,
        jclass   /*clazz*/,
        jlong    bridge_handle,
        jfloat   volume)
{
    if (!is_valid_bridge_handle(bridge_handle)) {
        ESBLOGW("nativeSetVolume: invalid bridge_handle=%lld — ignoring",
                static_cast<long long>(bridge_handle));
        return;
    }

    auto *bridge = reinterpret_cast<DecoderToRingBridge *>(bridge_handle);
    bridge->set_volume(static_cast<float>(volume));

    ESBLOGD("nativeSetVolume: bridge=%p volume=%.4f",
            static_cast<void *>(bridge),
            static_cast<float>(volume));
}


// ─────────────────────────────────────────────────────────────────────────────

/**
 * Replace the bridge's destination ring buffer when an engine context is
 * recycled (e.g. format change requires a new UsbDriverContext while reusing
 * the same decoder and bridge objects).
 *
 * Must only be called while the pump thread is stopped (after stop() and
 * before start()).  The typical call sequence for an in-session format change:
 *
 *   1. nativeDetachUsbEngine(old_bridge) — stops pump.
 *   2. nativeRelease(old_driver)         — tears down old USB context.
 *   3. nativeInitWithFileDescriptor(fd)  — new USB context + ring.
 *   4. nativeStartPlayback(new_driver)   — new ring populated.
 *   5. nativeSwapRingBuffer(bridge, new_ring_ptr) — re-wire.
 *   6. bridge.start()                   — resume pumping.
 *
 * In practice this is simpler to handle by creating a new bridge
 * (nativeAttachUsbEngine) than by calling this function; this entry point
 * exists for advanced integrations that want to avoid the decoder re-open cost.
 *
 * @param env              JNI environment (unused).
 * @param clazz            EngineSwapBridge class (unused).
 * @param bridge_handle    jlong from nativeAttachUsbEngine.
 * @param new_driver_handle jlong from the NEW nativeInitWithFileDescriptor.
 */
extern "C"
JNIEXPORT void JNICALL
Java_com_androidexpert35_audiophilemusicplayer_data_playback_usb_EngineSwapBridge_nativeSwapRingBuffer(
        JNIEnv  * /*env*/,
        jclass   /*clazz*/,
        jlong    bridge_handle,
        jlong    new_driver_handle)
{
    if (!is_valid_bridge_handle(bridge_handle) || !is_valid_driver_handle(new_driver_handle)) {
        ESBLOGE("nativeSwapRingBuffer: invalid handle(s) — bridge=%lld driver=%lld",
                static_cast<long long>(bridge_handle),
                static_cast<long long>(new_driver_handle));
        return;
    }

    auto *bridge     = reinterpret_cast<DecoderToRingBridge *>(bridge_handle);
    auto *new_ctx    = reinterpret_cast<UsbDriverContext *>(new_driver_handle);

    if (!new_ctx->ring_buffer) {
        ESBLOGE("nativeSwapRingBuffer: new UsbDriverContext has no ring buffer — "
                "call nativeStartPlayback on the new driver first");
        return;
    }

    bridge->reset_ring_buffer(new_ctx->ring_buffer.get());

    ESBLOGI("nativeSwapRingBuffer: ring replaced — new ring=%p (%zu KB)",
            static_cast<void *>(new_ctx->ring_buffer.get()),
            new_ctx->ring_buffer->capacity() / 1024UL);
}
