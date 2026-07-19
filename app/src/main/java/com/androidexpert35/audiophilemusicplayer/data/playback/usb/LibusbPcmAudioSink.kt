package com.androidexpert35.audiophilemusicplayer.data.playback.usb

import android.hardware.usb.UsbDeviceConnection
import android.os.SystemClock
import android.util.Log
import androidx.annotation.Keep
import com.androidexpert35.audiophilemusicplayer.data.playback.native_.PipelinePathReport
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * USB PCM audio output sink backed by the libusb isochronous transfer engine.
 *
 * Routes all PCM data — whether from a native PCM source or from a DSD file
 * decimated to 88 200 Hz float32 via lavfi+soxr — through the native
 * [DecoderToRingBridge] pump thread and libusb ISO OUT transfers.
 *
 * The Kotlin write loop in [BitPerfectPlaybackEngine] must **not** push PCM
 * frames to this sink; it detects [LibusbOutputSink] and switches to the
 * 50 ms EOF-polling mode instead.
 *
 * ### Construction contract
 *
 * The caller ([UsbAudioSinkFactory.createLibusbPcmSink]) is responsible for
 * completing the full libusb init sequence before passing handles here:
 *
 * 1. `nativeInitWithFileDescriptor(fd)` → [driverHandle]
 * 2. `nativeClaimInterface(...)` → success
 * 3. `nativeAllocateTransferPool(...)` → success
 * 4. `nativeStartPlayback(driverHandle)` → ISO transfers running (silence)
 * 5. `nativeOpenDecoderForUsb(path, forcePcm=true)` → [decoderHandle]
 *
 * [play] then wires the decoder to the ring buffer via
 * [EngineSwapBridge.nativeAttachUsbEngine], starting the pump thread.
 *
 * @property driverHandle      Opaque libusb context handle from
 *   [UsbAudioBridge.nativeInitWithFileDescriptor]. Released by [close].
 * @property decoderHandle     Opaque native decoder handle from
 *   [EngineSwapBridge.nativeOpenDecoderForUsb]. Released by [close].
 * @property sampleRateHz      Output sample rate used for wall-clock position math.
 * @property connection        [UsbDeviceConnection] whose FD libusb borrowed.
 *   Closed by [close].
 * @property pathReport        Diagnostic routing descriptor for telemetry surfaces.
 * @property volumeController  Singleton volume controller; [play] registers this
 *   sink's bridge handle so that volume key events propagate to the native scalar
 *   without the system volume dialog interfering.
 */
internal class LibusbPcmAudioSink(
    private val driverHandle: Long,
    private val decoderHandle: Long,
    private val sampleRateHz: Int,
    private val connection: UsbDeviceConnection,
    override val pathReport: PipelinePathReport,
    private val volumeController: UsbVolumeController,
) : LibusbOutputSink {

    // ── Native bridge handle ──────────────────────────────────────────────────

    /** Handle from [EngineSwapBridge.nativeAttachUsbEngine]; 0 when stopped. */
    private var bridgeHandle: Long = 0L

    // ── Position tracking ─────────────────────────────────────────────────────
    // Derived from wall-clock elapsed time; accuracy ~1 ms — sufficient for
    // seek-bar and notification updates.

    /** Wall-clock epoch captured when the pump starts. */
    private var playStartWallClockMs: Long = 0L

    /** Accumulated PCM frame position at the last pause/seek boundary. */
    private var pausedAtFrames: Long = 0L

    /**
     * Seek target requested while the pump was not attached (`bridgeHandle == 0`),
     * in microseconds from stream start. [seekTo] cannot apply this to the native
     * decoder immediately in that state — there is no bridge handle yet — so it is
     * only reflected in [pausedAtFrames] for position-clock purposes. [play] must
     * apply it to the native decoder right after attaching, otherwise the pump
     * starts decoding from the beginning of the stream while the Kotlin-side
     * position clock keeps reporting the seeked-to position, playing silently out
     * of sync with what the UI shows.
     */
    private var pendingSeekUs: Long? = null

    // ── State ─────────────────────────────────────────────────────────────────

    @Volatile private var isPlaying: Boolean = false
    @Volatile private var closed: Boolean = false

    /**
     * Set to `true` by the native pump thread when end-of-stream is reached.
     * Polled by the engine's write-loop override.
     */
    override val eofFlag: AtomicBoolean = AtomicBoolean(false)

    // ── EOF listener ─────────────────────────────────────────────────────────

    /**
     * Receives the end-of-stream notification from the native pump thread.
     * The JNI layer calls `onDecoderEof()V` exactly once.
     */
    @Keep
    private inner class EofListener {
        /** Called from the native pump thread when [decoderHandle] reports EOF. */
        @Keep
        fun onDecoderEof() {
            eofFlag.set(true)
            Log.d(TAG, "onDecoderEof: native pump reached end of stream")
        }
    }

    private val eofListener = EofListener()

    // ── AudiophileOutputSink ──────────────────────────────────────────────────

    /**
     * Starts the native pump thread, wiring [decoderHandle] to the libusb ring
     * buffer. PCM audio begins flowing through the ISO OUT transfers.
     *
     * The current volume from [volumeController] is passed directly to
     * [EngineSwapBridge.nativeAttachUsbEngine] as `initialVolume` so the C++
     * bridge applies the quadratic taper and stores the gain scalar **before**
     * the pump thread is started. This is the pre-flight volume check that
     * eliminates the startup volume blast caused by the pump racing ahead of
     * a post-attach [EngineSwapBridge.nativeSetVolume] call.
     *
     * [volumeController] is then registered via [UsbVolumeController.attachBridge]
     * for all subsequent volume changes made by the user.
     *
     * No-op if already playing.
     *
     * @throws IOException when [EngineSwapBridge.nativeAttachUsbEngine] fails.
     */
    override fun play() {
        checkNotClosed()
        if (isPlaying) return

        // Compute the initial linear volume position before the pump thread
        // starts.  Passing it directly to nativeAttachUsbEngine ensures the
        // C++ bridge applies the quadratic taper and stores the gain scalar
        // BEFORE bridge->start() is called, so every pre-fill chunk pushed
        // into the ring is already at the correct amplitude — no startup blast
        // or brief silence gap caused by the old post-attach ordering.
        val initialVolume = volumeController.volumePct.value / 100f

        // Apply any seek that was requested while the pump was detached (e.g. a
        // seek right after loading a track, or a resume after an idle-sink
        // release) BEFORE attaching the engine. nativeAttachUsbEngine pre-fills
        // the ring from the decoder's current position and the ring is never
        // cleared afterwards; a seek applied only after the attach leaves
        // start-of-track audio in the ring, which the DAC audibly plays for a
        // moment before jumping to the target position (the "track restarts for
        // a second" glitch when a settings toggle reloads the session).
        val preAttachSeekUs = pendingSeekUs
        if (preAttachSeekUs != null) {
            pendingSeekUs = null
            val seekOk = EngineSwapBridge.nativeSeekUsbDecoder(decoderHandle, preAttachSeekUs)
            Log.d(TAG, "play: applied pre-attach seek positionUs=$preAttachSeekUs seekOk=$seekOk")
        }

        val handle = EngineSwapBridge.nativeAttachUsbEngine(
            driverHandle, decoderHandle, eofListener, initialVolume
        )
        // ARM64 MTE/HWASan: heap pointers have top-byte set → appear negative as jlong.
        // NEVER check `handle > 0L` — use isValidBridgeHandle() which only rejects the
        // known small-negative SWAP_ERR_* codes and the null sentinel.
        if (!EngineSwapBridge.isValidBridgeHandle(handle)) {
            throw IOException("LibusbPcmAudioSink.play: nativeAttachUsbEngine failed (code=$handle)")
        }
        bridgeHandle = handle
        volumeController.attachBridge(handle)

        playStartWallClockMs = SystemClock.elapsedRealtime()
        isPlaying = true

        Log.d(TAG, "play: pump attached — bridgeHandle=$bridgeHandle sampleRateHz=$sampleRateHz")
    }

    /**
     * Stops the pump thread; the ring buffer drains naturally before silence.
     * No-op if already paused.
     */
    override fun pause() {
        if (!isPlaying) return
        pausedAtFrames = getPlaybackHeadPositionFrames()
        stopNativePump()
        isPlaying = false
        Log.d(TAG, "pause: pump detached pausedAtFrames=$pausedAtFrames")
    }

    /** Equivalent to [pause] on the libusb path. */
    override fun stop() = pause()

    /**
     * No-op flush — the libusb ring buffer is not directly flushable from Kotlin.
     * A seek via [seekTo] is the correct way to discard buffered audio.
     */
    override fun flush() = Unit

    /**
     * No-op write — data is fed to the ring buffer by the native pump thread.
     * Returns [size] so the engine's write loop treats this as a success.
     *
     * [BitPerfectPlaybackEngine] detects [LibusbOutputSink] and bypasses the
     * normal FFmpeg read → encode → write cycle entirely; this method is only
     * here to satisfy the [com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile.AudiophileOutputSink]
     * contract.
     */
    override fun write(buffer: ByteBuffer, size: Int): Int = size

    /**
     * Returns the estimated playback head in PCM frames, derived from
     * wall-clock elapsed time since [play] was last called.
     */
    override fun getPlaybackHeadPositionFrames(): Long {
        if (!isPlaying || playStartWallClockMs == 0L) return pausedAtFrames
        val elapsedMs = SystemClock.elapsedRealtime() - playStartWallClockMs
        return pausedAtFrames + elapsedMs * sampleRateHz / 1_000L
    }

    /**
     * Performs the full libusb PCM teardown in the correct destruction order:
     *
     * 1. Stop pump thread ([EngineSwapBridge.nativeDetachUsbEngine]).
     * 2. Free native decoder ([EngineSwapBridge.nativeReleaseUsbDecoder]).
     * 3. Release libusb context ([UsbAudioBridge.nativeRelease]).
     * 4. Close Android USB device connection.
     *
     * Safe to call multiple times.
     */
    override fun close() {
        if (closed) return
        closed = true
        Log.d(TAG, "close: tearing down libusb PCM resources")
        stopNativePump()
        EngineSwapBridge.nativeReleaseUsbDecoder(decoderHandle)
        UsbAudioBridge.nativeRelease(driverHandle)
        runCatching { connection.close() }
            .onFailure { e -> Log.w(TAG, "UsbDeviceConnection.close failed", e) }
        Log.d(TAG, "close: done")
    }

    // ── LibusbOutputSink ──────────────────────────────────────────────────────

    /**
     * Seeks the native pump and decoder to [positionMs] from stream start.
     *
     * Stops the pump, seeks the native decoder, and restarts the pump on
     * success. The wall-clock position epoch is reset so
     * [getPlaybackHeadPositionFrames] reports the correct value immediately.
     *
     * @param positionMs Target seek position in milliseconds from stream start.
     * @return `true` when the native seek succeeded.
     */
    override fun seekTo(positionMs: Long): Boolean {
        checkNotClosed()
        val positionUs = positionMs * 1_000L

        val seekOk = if (bridgeHandle != 0L) {
            pendingSeekUs = null
            EngineSwapBridge.nativePumpBridgeSeek(bridgeHandle, decoderHandle, positionUs)
        } else {
            // Pump stopped (e.g. paused, or not yet attached after a fresh load).
            // Stash the target so play() can apply it to the native decoder right
            // after attaching the pump — see pendingSeekUs.
            pendingSeekUs = positionUs
            Log.d(TAG, "seekTo: pump not running — position pending for next play()")
            true
        }

        pausedAtFrames = positionMs * sampleRateHz / 1_000L
        if (isPlaying) playStartWallClockMs = SystemClock.elapsedRealtime()

        Log.d(TAG, "seekTo: positionMs=$positionMs seekOk=$seekOk")
        return seekOk
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun stopNativePump() {
        val handle = bridgeHandle
        // `bridgeHandle` is set to 0L initially and after detach; any non-zero value
        // was previously validated by isValidBridgeHandle() before assignment, so
        // a simple != 0L guard is sufficient (and avoids the MTE `> 0L` pitfall).
        if (handle != 0L) {
            EngineSwapBridge.nativeDetachUsbEngine(handle)
            bridgeHandle = 0L
            volumeController.detachBridge()
        }
    }

    private fun checkNotClosed() {
        check(!closed) { "LibusbPcmAudioSink is already closed" }
    }

    private companion object {
        const val TAG = "LibusbPcmSink"
    }
}

