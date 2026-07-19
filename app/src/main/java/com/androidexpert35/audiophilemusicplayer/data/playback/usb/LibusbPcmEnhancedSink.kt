package com.androidexpert35.audiophilemusicplayer.data.playback.usb

import android.hardware.usb.UsbDeviceConnection
import android.os.SystemClock
import android.util.Log
import com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile.AudiophileOutputSink
import com.androidexpert35.audiophilemusicplayer.data.playback.native_.PipelinePathReport
import java.nio.ByteBuffer

/**
 * Libusb PCM output sink driven by the **Kotlin write loop** rather than the native
 * decoder pump thread.
 *
 * ### When this sink is used
 *
 * When an FFmpeg DSP stage — Sonic Upscaling Enhancer (SUE) or Hi-Res Dynamic
 * Remaster — is active alongside a libusb-ready USB DAC, the Kotlin write loop
 * in [BitPerfectPlaybackEngine] process each decoded chunk through the active
 * [SueStage] to produce float32 PCM at the enhanced output rate, then writes
 * the result here via [write]. The ISO OUT transfers consume from the same SPSC
 * ring buffer as [LibusbPcmAudioSink]; the only difference is who fills the ring.
 *
 * ### Contrast with [LibusbPcmAudioSink]
 *
 * | Aspect | [LibusbPcmAudioSink] | [LibusbPcmEnhancedSink] |
 * |--------|----------------------|--------------------------|
 * | Data source | Native C++ pump thread | Kotlin write loop |
 * | SUE / Hi-Res Remaster | Not supported | Applied by Kotlin pipeline |
 * | Native decoder handle | Yes | None |
 * | `write()` | No-op | Pushes data to ring via JNI |
 * | [LibusbOutputSink] | Yes — EOF polling mode | **No** — normal write loop |
 *
 * ### Why this sink does NOT implement [LibusbOutputSink]
 *
 * [LibusbOutputSink] signals [BitPerfectPlaybackEngine] to skip the PCM write
 * path and poll the native EOF flag every 50 ms. This sink intentionally avoids
 * that interface so the Kotlin write loop remains active and can process each
 * decoded chunk through the FFmpeg DSP pipeline before every ring write.
 *
 * ### Sample rate
 *
 * [sampleRateHz] is the **DSP output rate** (e.g. 88 200 Hz when SUE upsamples a
 * 44 100 Hz source). The USB clock and ISO transfer pool are configured at this
 * rate when the sink is created via [UsbAudioSinkFactory.createLibusbPcmEnhancedSink].
 *
 * ### Volume control
 *
 * This sink has no decoder-pump bridge handle, so it samples
 * [UsbVolumeController.volumePct] on each write. The native float-to-S32LE boundary
 * applies the same volume curve as the decoder-pump route before quantisation.
 *
 * @property driverHandle  Opaque libusb context handle from
 *   [UsbAudioBridge.nativeInitWithFileDescriptor]. Owns the SPSC ring buffer;
 *   released by [close].
 * @property sampleRateHz  Effective output sample rate used for wall-clock position
 *   tracking and ISO pool sizing. This is the DSP output rate, not the source rate.
 * @property connection    [UsbDeviceConnection] whose FD libusb borrowed. Closed by [close].
 * @property pathReport    Diagnostic routing descriptor for telemetry surfaces.
 * @property volumeController Direct-USB volume source sampled for every ring write.
 */
internal class LibusbPcmEnhancedSink(
    private val driverHandle: Long,
    private val sampleRateHz: Int,
    private val connection: UsbDeviceConnection,
    override val pathReport: PipelinePathReport,
    private val volumeController: UsbVolumeController,
) : AudiophileOutputSink {

    // ── Position tracking ─────────────────────────────────────────────────────
    // Wall-clock based; ~1 ms accuracy — sufficient for seek bar and notification.

    /** Wall-clock epoch captured when [play] starts or [flush] resets the anchor. */
    private var playStartWallClockMs: Long = 0L

    /**
     * Accumulated PCM frame position at the last [pause] or [flush] boundary.
     *
     * Reset to 0 by [flush] so [getPlaybackHeadPositionFrames] returns 0 after
     * a seek, keeping it consistent with the engine's `sinkStartFrames = 0`.
     */
    private var pausedAtFrames: Long = 0L

    // ── State ─────────────────────────────────────────────────────────────────

    @Volatile private var isPlaying: Boolean = false
    @Volatile private var closed: Boolean = false

    // ── AudiophileOutputSink ──────────────────────────────────────────────────

    /**
     * Marks the sink as active so position tracking starts.
     *
     * Unlike [LibusbPcmAudioSink], there is no decoder pump to attach here. The
     * SPSC ring and event thread were prepared by [UsbAudioBridge.nativeStartPlayback]
     * inside [UsbAudioSinkFactory.createLibusbPcmEnhancedSink]; the first native
     * ring write submits the ISO transfer pool after real audio has been buffered.
     *
     * No-op if already playing.
     *
     * @throws IllegalStateException when called after [close].
     */
    override fun play() {
        checkNotClosed()
        if (isPlaying) return
        playStartWallClockMs = SystemClock.elapsedRealtime()
        isPlaying = true
        volumeController.attachDirectWriter()
        Log.d(TAG, "play: Kotlin-write ISO sink active sampleRateHz=$sampleRateHz")
    }

    /**
     * Pauses position tracking.
     *
     * The SPSC ring continues draining through live ISO transfers until silence.
     * No data enters the ring while paused because the Kotlin write loop stops.
     *
     * No-op if already paused.
     */
    override fun pause() {
        if (!isPlaying) return
        pausedAtFrames = getPlaybackHeadPositionFrames()
        isPlaying = false
        volumeController.detachBridge()
        Log.d(TAG, "pause: pausedAtFrames=$pausedAtFrames")
    }

    /** Equivalent to [pause] — this sink has no independent stop mechanism. */
    override fun stop() = pause()

    /**
     * Resets the wall-clock position anchor for accurate position reporting after
     * a seek.
     *
     * [BitPerfectPlaybackEngine.seekTo] calls `flush()` followed by setting
     * `sinkStartFrames = 0`. Resetting [pausedAtFrames] and [playStartWallClockMs]
     * here keeps [getPlaybackHeadPositionFrames] returning 0 after the seek so
     * it stays consistent with the `sinkStartFrames` anchor used in
     * `calculateBitPerfectPlaybackPositionMs`.
     *
     * **Brief ring-drain gap**: the SPSC ring is not cleared, so a short burst of
     * pre-seek audio (~43 ms at ring capacity) may be heard before the Kotlin write
     * loop delivers post-seek data. This is the same accepted behaviour as on the
     * [LibusbPcmAudioSink] path and is not audible at normal seek rates.
     */
    override fun flush() {
        pausedAtFrames = 0L
        if (isPlaying) playStartWallClockMs = SystemClock.elapsedRealtime()
    }

    /**
     * Pushes [size] bytes from [buffer] into the SPSC ring buffer consumed by the
     * ISO OUT transfers, applying natural back-pressure until space is available.
     *
     * Data must be float32 interleaved PCM at [sampleRateHz] — the format produced
     * by the active [SueStage] on the audio thread. Delegates to
     * [EngineSwapBridge.nativeWriteToRingBuffer], which blocks until all bytes are
     * written or the ring reports an error, mirroring [android.media.AudioTrack.write]
     * back-pressure semantics.
     *
     * @param buffer Direct [ByteBuffer] with float32 PCM (position = 0).
     * @param size   Number of valid bytes to write starting at position 0.
     * @return [size] on success; a negative error code on ring failure.
     * @throws IllegalStateException when called after [close].
     */
    override fun write(buffer: ByteBuffer, size: Int): Int {
        checkNotClosed()
        return EngineSwapBridge.nativeWriteToRingBuffer(
            driverHandle = driverHandle,
            buffer = buffer,
            byteCount = size,
            volume = volumeController.volumePct.value / 100f,
        )
    }

    /**
     * Returns the estimated playback-head position in PCM frames, derived from
     * wall-clock elapsed time since the last [play] or [flush] call.
     *
     * Uses [sampleRateHz] (the DSP output rate) so the result is consistent with
     * [BitPerfectPlaybackEngine]'s `playbackClockRateHz`, which is also set to
     * the SUE / Hi-Res output rate for this path.
     *
     * Returns [pausedAtFrames] when not playing or before the first [play] call.
     */
    override fun getPlaybackHeadPositionFrames(): Long {
        if (!isPlaying || playStartWallClockMs == 0L) return pausedAtFrames
        val elapsedMs = SystemClock.elapsedRealtime() - playStartWallClockMs
        return pausedAtFrames + elapsedMs * sampleRateHz / 1_000L
    }

    /**
     * Releases all libusb resources in the correct teardown order:
     *
     * 1. Stop position tracking.
     * 2. Release libusb context ([UsbAudioBridge.nativeRelease]) — cancels in-flight
     *    ISO transfers, joins the event thread, and frees the ring buffer.
     * 3. Close the Android USB device connection.
     *
     * Safe to call multiple times; subsequent calls are no-ops.
     */
    override fun close() {
        if (closed) return
        closed = true
        isPlaying = false
        volumeController.detachBridge()
        Log.d(TAG, "close: releasing enhanced libusb PCM resources driverHandle=0x${driverHandle.toULong().toString(16)}")
        UsbAudioBridge.nativeRelease(driverHandle)
        runCatching { connection.close() }
            .onFailure { e -> Log.w(TAG, "UsbDeviceConnection.close failed", e) }
        Log.d(TAG, "close: done")
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun checkNotClosed() = check(!closed) { "LibusbPcmEnhancedSink is already closed" }

    private companion object {
        const val TAG = "LibusbPcmEnhancedSink"
    }
}
