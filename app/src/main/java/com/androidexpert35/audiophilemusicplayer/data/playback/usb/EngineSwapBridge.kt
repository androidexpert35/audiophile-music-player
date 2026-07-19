@file:Suppress("JniMissingFunction")

package com.androidexpert35.audiophilemusicplayer.data.playback.usb

import com.androidexpert35.audiophilemusicplayer.data.playback.usb.EngineSwapBridge.isValidBridgeHandle
import com.androidexpert35.audiophilemusicplayer.data.playback.usb.EngineSwapBridge.nativeAttachUsbEngine
import com.androidexpert35.audiophilemusicplayer.data.playback.usb.EngineSwapBridge.nativeBridgeIsEof
import com.androidexpert35.audiophilemusicplayer.data.playback.usb.EngineSwapBridge.nativeDetachUsbEngine
import com.androidexpert35.audiophilemusicplayer.data.playback.usb.EngineSwapBridge.nativeOpenDecoderForUsb
import com.androidexpert35.audiophilemusicplayer.data.playback.usb.EngineSwapBridge.nativePumpBridgeSeek
import com.androidexpert35.audiophilemusicplayer.data.playback.usb.EngineSwapBridge.nativeReleaseUsbDecoder
import com.androidexpert35.audiophilemusicplayer.data.playback.usb.EngineSwapBridge.nativeSetVolume
import java.nio.ByteBuffer


/**
 * JNI bridge to the native decoder→ring-buffer pump engine (`engine_swap_bridge.cpp`).
 *
 * Provides the wiring sequence that connects a native `FfmpegAudioDecoder` to the
 * libusb `SpscRingBuffer` via a `DecoderToRingBridge` pump thread:
 *
 * ```
 * nativeOpenDecoderForUsb(path, forcePcm)   // open native FFmpeg decoder
 *   → nativeAttachUsbEngine(driver, decoder, listener, initialVolume)  // wire decoder → ring, set volume, start pump
 *   ← bridgeHandle
 *   → nativePumpBridgeSeek(bridge, decoder, positionUs) // seek without re-opening decoder
 *   → nativeBridgeIsEof(bridge)              // poll for end-of-stream
 *   → nativeDetachUsbEngine(bridge)          // stop pump, destroy bridge
 *   → nativeReleaseUsbDecoder(decoder)       // free native FFmpeg decoder
 * ```
 *
 * All returned handles are opaque [Long] pointers to native heap objects. A handle
 * becomes invalid after its corresponding release call and must not be reused.
 *
 * @see UsbAudioBridge for the upstream libusb driver context lifecycle.
 */
internal object EngineSwapBridge {

    // ── Decoder ───────────────────────────────────────────────────────────────────

    /**
     * Opens a native `FfmpegAudioDecoder` for the audio file at [path].
     *
     * The decoder is a C++ object not visible to Kotlin; its only Kotlin-accessible
     * operations are [nativePumpBridgeSeek] (via the bridge) and
     * [nativeReleaseUsbDecoder].
     *
     * Pass `forcePcm = false` to obtain raw one-bit DSD bytes for native DSD or
     * DoP transport. Pass `true` to let FFmpeg decode DSD to 32-bit float PCM.
     *
     * @param path      Filesystem path to the audio source file.
     * @param forcePcm  `false` to retain raw DSD output; `true` to decode to PCM.
     * @return Native decoder handle (> 0) on success; 0 on failure.
     */
    @JvmStatic
    external fun nativeOpenDecoderForUsb(path: String, forcePcm: Boolean): Long

    /**
     * Destroys the native decoder opened by [nativeOpenDecoderForUsb].
     *
     * **Must be called after [nativeDetachUsbEngine]** to guarantee no concurrent
     * `read_pcm` / `read_dsd` calls are in-flight from the pump thread when the
     * decoder is freed.
     *
     * Safe to call with `decoderHandle = 0` (no-op).
     *
     * @param decoderHandle Handle from [nativeOpenDecoderForUsb].
     */
    @JvmStatic
    external fun nativeReleaseUsbDecoder(decoderHandle: Long)

    // ── Engine attachment ─────────────────────────────────────────────────────────

    /**
     * Wires [decoderHandle] to the libusb ring buffer owned by [driverHandle] and
     * starts the pump thread.
     *
     * The pump thread calls `decoder->read_dsd()` or `decoder->read_pcm()` in a
     * tight loop and pushes decoded bytes to the ring buffer. The ISO transfer
     * callback in `IsoTransferPool` pops those bytes on each transfer completion.
     *
     * [UsbAudioBridge.nativeStartPlayback] or [UsbAudioBridge.nativeStartDsdPlayback]
     * must have been called successfully before this function — both create the
     * `SpscRingBuffer` that the bridge writes into.
     *
     * ### Pre-flight volume
     *
     * [initialVolume] is applied to the native bridge via `set_volume()` **before**
     * the pump thread is started. This ensures every pre-fill chunk entering the
     * ring is already at the correct amplitude — no brief silence gap or startup
     * blast caused by the pump racing ahead of the post-attach [nativeSetVolume]
     * call. Pass `UsbVolumeController.volumePct.value / 100f` as the argument.
     *
     * ### EOF notification
     * When [onEofListener] is non-null it must implement:
     * ```kotlin
     * fun onDecoderEof()
     * ```
     * The method is called exactly once from the pump thread via JNI when the
     * decoder signals end-of-stream. As an alternative, the caller may poll
     * [nativeBridgeIsEof] on a timer.
     *
     * @param driverHandle    Context handle from [UsbAudioBridge.nativeInitWithFileDescriptor].
     * @param decoderHandle   Decoder handle from [nativeOpenDecoderForUsb].
     * @param onEofListener   Kotlin object with `fun onDecoderEof()`, or `null`.
     * @param initialVolume   Raw UI volume position in `[0.0, 1.0]` applied to the
     *                        bridge before the pump thread starts.  Prevents the brief
     *                        silence gap the old post-attach ordering produced.
     * @return Bridge handle on success (validated with [isValidBridgeHandle]);
     *   a small-negative error code on failure (see `SWAP_ERR_*` in `engine_swap_bridge.cpp`).
     */
    @JvmStatic
    external fun nativeAttachUsbEngine(
        driverHandle: Long,
        decoderHandle: Long,
        onEofListener: Any?,
        initialVolume: Float,
    ): Long

    /**
     * Stops the pump thread and destroys the bridge.
     *
     * After this call the bridge handle is invalid. The ring buffer retains any
     * buffered audio; the ISO callback drains it before falling back to silence.
     * Call [nativeReleaseUsbDecoder] separately when the decoder is no longer
     * needed.
     *
     * Safe to call with `bridgeHandle ≤ 0` (no-op).
     *
     * @param bridgeHandle Handle from [nativeAttachUsbEngine].
     */
    @JvmStatic
    external fun nativeDetachUsbEngine(bridgeHandle: Long)

    // ── Seek ──────────────────────────────────────────────────────────────────────

    /**
     * Seeks the underlying decoder to [positionUs] microseconds from stream start.
     *
     * The pump thread is halted before the seek and restarted on success so no
     * pre-seek bytes enter the ring during the codec flush. The ring is NOT cleared
     * (to avoid races with the ISO callback); a brief silence gap of ~43 ms
     * (ring-capacity / byte-rate) naturally separates pre-seek and post-seek audio.
     *
     * If the seek fails the pump thread is **not** restarted; the caller should
     * close and re-open the decoder.
     *
     * @param bridgeHandle  Handle from [nativeAttachUsbEngine].
     * @param decoderHandle Handle from [nativeOpenDecoderForUsb].
     * @param positionUs    Target position in microseconds from stream start.
     * @return `true` on success; `false` if the underlying decoder seek failed.
     */
    @JvmStatic
    external fun nativePumpBridgeSeek(
        bridgeHandle: Long,
        decoderHandle: Long,
        positionUs: Long,
    ): Boolean

    /**
     * Seeks a not-yet-attached USB decoder to [positionUs] microseconds.
     *
     * Must only be called while **no pump thread is attached** to
     * [decoderHandle] (i.e. before [nativeAttachUsbEngine], or after
     * [nativeDetachUsbEngine]) — the decoder has a single-owner contract.
     *
     * This is the pre-attach counterpart of [nativePumpBridgeSeek]: applying a
     * pending seek here, *before* the pump attaches, guarantees the ring buffer
     * is pre-filled with post-seek audio from the very first chunk. Seeking
     * after the attach lets the pre-fill run from position 0 and — because the
     * ring is never cleared — the DAC audibly plays the start of the track for
     * a moment before jumping to the target position.
     *
     * @param decoderHandle Handle from [nativeOpenDecoderForUsb].
     * @param positionUs    Target position in microseconds from stream start.
     * @return `true` on success; `false` if the decoder seek failed.
     */
    @JvmStatic
    external fun nativeSeekUsbDecoder(
        decoderHandle: Long,
        positionUs: Long,
    ): Boolean

    // ── EOF poll ──────────────────────────────────────────────────────────────────

    /**
     * Returns `true` when the pump thread has reached end-of-stream.
     *
     * Thread-safe atomic flag read. Use as an alternative to the [onEofListener]
     * callback when a polling model is preferred.
     *
     * Returns `false` for `bridgeHandle ≤ 0`.
     *
     * @param bridgeHandle Handle from [nativeAttachUsbEngine].
     */
    @JvmStatic
    external fun nativeBridgeIsEof(bridgeHandle: Long): Boolean

    // ── Ring-buffer swap ──────────────────────────────────────────────────────────

    /**
     * Replaces the bridge's destination ring buffer with the one owned by
     * [newDriverHandle].
     *
     * Intended for advanced in-session format changes where the driver context is
     * rebuilt but the decoder and bridge objects are reused to avoid the decoder
     * re-open cost. The pump thread must be stopped (via [nativeDetachUsbEngine]
     * and then `start()`) around this call, and [UsbAudioBridge.nativeStartPlayback]
     * must have been called on [newDriverHandle] first.
     *
     * @param bridgeHandle     Handle from [nativeAttachUsbEngine] (pump must be stopped).
     * @param newDriverHandle  Handle from the new [UsbAudioBridge.nativeInitWithFileDescriptor].
     */
    @JvmStatic
    external fun nativeSwapRingBuffer(bridgeHandle: Long, newDriverHandle: Long)

    // ── Kotlin-write ring buffer path ─────────────────────────────────────────────

    /**
     * Converts [byteCount] bytes of interleaved float32 PCM from [buffer] to
     * signed S32LE wire samples and writes them into the SPSC ring buffer owned
     * by [driverHandle].
     *
     * This function enables the Kotlin write loop to feed FFmpeg-processed PCM
     * (SUE / Hi-Res Dynamic Remaster output) into the libusb ISO transfer engine
     * without the native decoder pump thread. It is used exclusively by
     * [LibusbPcmEnhancedSink] when an FFmpeg DSP stage is active alongside a
     * libusb-ready USB DAC.
     *
     * On the first non-empty write the native side pre-fills the ring with real
     * audio before submitting the ISO transfer pool. Subsequent calls block until
     * all [byteCount] bytes have been written or a bounded back-pressure timeout
     * occurs, providing the same semantics as
     * [android.media.AudioTrack.write] with `WRITE_BLOCKING` — the Kotlin audio
     * thread is naturally rate-limited by the ISO drain rate.
     *
     * ### Prerequisites
     * [UsbAudioBridge.nativeStartPlayback] must have been called successfully on
     * [driverHandle] before this function is invoked — i.e. the SPSC ring and ISO
     * transfer pool must already be running.
     *
     * ### Threading
     * Must be called from [BitPerfectPlaybackEngine]'s dedicated audio thread only.
     * The ring is single-producer (this call) / single-consumer (ISO transfer
     * callback). Concurrent calls from multiple threads are not safe.
     *
     * @param driverHandle Context handle from [UsbAudioBridge.nativeInitWithFileDescriptor].
     * @param buffer       Direct [ByteBuffer] containing the PCM data to write.
     *                     Bytes are read starting at position 0.
     * @param byteCount    Number of float32 input bytes to convert and write.
     * @param volume       Raw USB volume position in `[0.0, 1.0]`; the native
     *   writer applies the same quadratic taper as the decoder-pump path before
     *   quantising to S32LE.
     * @return [byteCount] on success; a negative error code on ring failure or when
     *   the driver handle is invalid.
     */
    @JvmStatic
    external fun nativeWriteToRingBuffer(
        driverHandle: Long,
        buffer: ByteBuffer,
        byteCount: Int,
        volume: Float,
    ): Int

    // ── Software volume control ───────────────────────────────────────────────────

    /**
     * Sets the software volume scalar applied to every PCM sample before it enters
     * the SPSC ring buffer.
     *
     * Because the libusb engine bypasses Android AudioFlinger entirely, the system
     * volume rocker has no effect on playback level. This function is the only
     * volume control available on the direct-USB path.
     *
     * ### Precision
     *
     * For S16LE sources (MP3, FLAC 16-bit, AAC) the multiplication is performed
     * in 32-bit float **before** the 16→32-bit left-shift, preserving the full
     * dynamic range of the original sample in the output 32-bit word.
     *
     * For S32LE (24-bit lossless) and float32 (DSD-decimated) sources the volume
     * is applied in-place via float arithmetic before the chunk enters the ring.
     *
     * ### Granularity
     *
     * The scalar is read once per pump chunk (≈ 1 ms of audio at 44.1 kHz).
     * Volume changes therefore take effect within one chunk boundary — no audible
     * zipper noise for typical volume step sizes.
     *
     * @param bridgeHandle Handle from [nativeAttachUsbEngine]. Ignored if invalid.
     * @param volume       Linear amplitude scalar in `[0.0, 1.0]`.
     *                     - `0.0f` = full mute.
     *                     - `1.0f` = full scale (no attenuation).
     *                     Values outside this range are clamped by the native layer.
     */
    @JvmStatic
    external fun nativeSetVolume(bridgeHandle: Long, volume: Float)

    // ── Handle validation ─────────────────────────────────────────────────────────

    /**
     * Returns `true` when [handle] is a valid bridge handle returned by
     * [nativeAttachUsbEngine].
     *
     * On ARM64 devices with MTE (Memory Tagging Extension) or HWASan enabled,
     * the allocator sets the top byte of every heap pointer. The resulting address
     * (e.g. `0xb40000751ff31100`) has bit 63 set and therefore appears **negative**
     * as a signed `jlong`. Checking `handle > 0L` would incorrectly reject these
     * fully-valid pointers.
     *
     * This helper rejects only:
     * - `0L`  — uninitialised / null
     * - `-1L` — `SWAP_ERR_BAD_DRIVER_HANDLE`
     * - `-2L` — `SWAP_ERR_BAD_DECODER_HANDLE`
     * - `-3L` — `SWAP_ERR_RING_NOT_READY`
     * - `-4L` — `SWAP_ERR_OOM`
     * - `-5L` — `SWAP_ERR_PUMP_FAILED`
     * - `-6L` — `SWAP_ERR_SUBMIT_FAILED`
     *
     * All other values — including large-negative MTE-tagged addresses — are
     * accepted as valid.
     *
     * @param handle Value returned by [nativeAttachUsbEngine].
     * @return `true` when [handle] is safe to pass to [nativeDetachUsbEngine],
     *   [nativePumpBridgeSeek], and [nativeBridgeIsEof].
     */
    fun isValidBridgeHandle(handle: Long): Boolean =
        handle != 0L &&
        handle != -1L &&
        handle != -2L &&
        handle != -3L &&
        handle != -4L &&
        handle != -5L &&
        handle != -6L
}
