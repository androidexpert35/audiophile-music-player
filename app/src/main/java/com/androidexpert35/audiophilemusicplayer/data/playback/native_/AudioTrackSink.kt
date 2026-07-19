package com.androidexpert35.audiophilemusicplayer.data.playback.native_

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioTrack
import android.util.Log
import com.androidexpert35.audiophilemusicplayer.data.playback.AudioAttributesFactory
import com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile.AudiophileOutputSink
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Low-level audiophile AudioTrack wrapper.
 *
 * Negotiates the highest-fidelity output path the current HAL will allow via
 * the three-rung fallback chain implemented in [buildAudioTrackWithFallback]:
 *
 * 1. `FLAG_DIRECT` + native encoding (source bit-depth, source sample rate).
 * 2. `FLAG_DIRECT` + `PCM_FLOAT` (integer decoders the HAL refused).
 * 3. Standard mixer path at the native encoding (last resort).
 *    **Suppressed when [requireDirectOutput] = `true`** — used for DoP (DSD-over-PCM)
 *    transports that must not be routed through the software mixer because the
 *    volume/gain processing there would corrupt the DoP marker pattern.
 *
 * The owning [com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile.BitPerfectPlaybackEngine]
 * calls [write] on its dedicated audio thread; no internal locking is required.
 *
 * @property context Application context (used for `AudioManager` queries).
 * @property format Decoded audio format produced by [FFmpegDecoder].
 * @property bufferMultiplier Multiplier applied to
 *   `AudioTrack.getMinBufferSize()` — 3× is a safe default, 2× minimises
 *   latency, 4× maximises underrun resilience.
 * @property requireDirectOutput When `true`, throws if the OS refuses a
 *   `FLAG_DIRECT` path so the caller never silently downgrades to the mixer.
 */
class AudioTrackSink(
    private val context: Context,
    val format: AudioFormatInfo,
    private val bufferMultiplier: Int = DEFAULT_BUFFER_MULTIPLIER,
    private val requireDirectOutput: Boolean = false,
) : AudiophileOutputSink {

    private val audioAttributes: AudioAttributes = AudioAttributesFactory.createMediaAttributes()

    private val track: AudioTrack
    override val pathReport: PipelinePathReport

    /**
     * Cached ByteBuffer view over the most-recent write buffer.
     *
     * `AudioTrack.write(byte[], ...)` is **rejected with `ERROR_INVALID_OPERATION`
     * (-3) for `ENCODING_PCM_FLOAT` tracks on every Android version** — the
     * platform gates the byte-array overload to byte-oriented integer encodings
     * only. We therefore route all writes through `AudioTrack.write(ByteBuffer,
     * int, int)`, which accepts every PCM encoding.
     *
     * The buffer is re-wrapped lazily in [write] when the source `ByteArray`
     * identity changes, so steady-state playback performs zero allocations —
     * [ByteBuffer.wrap] is a thin view, not a copy.
     */
    private var writeView: ByteBuffer? = null

    init {
        val (built, report) = buildAudioTrackWithFallback(
            context = context,
            format = format,
            bufferMultiplier = bufferMultiplier,
            attributes = audioAttributes,
            requireDirectOutput = requireDirectOutput,
        )
        track = built
        pathReport = report
        Log.d(TAG, "Sink ready: $report")
    }

    /** Starts writing samples to the HAL. */
    override fun play() {
        if (track.state == AudioTrack.STATE_INITIALIZED) track.play()
    }

    /** Pauses without dropping the ring buffer contents. */
    override fun pause() {
        if (track.playState != AudioTrack.PLAYSTATE_STOPPED) track.pause()
    }

    /**
     * Drops any pending samples in the ring buffer — used on seek and on
     * format-preserving gapless track transitions that still want to discard
     * the previous track's tail.
     */
    override fun flush() {
        track.pause()
        track.flush()
    }

    /**
     * Stops the track explicitly. The subsequent [play] call will wait for
     * AudioTrack to refill — use [flush] instead when only the buffered data
     * needs discarding.
     */
    override fun stop() {
        if (track.playState != AudioTrack.PLAYSTATE_STOPPED) track.stop()
    }

    /** Releases HAL resources. Must be called exactly once per sink. */
    override fun close() {
        runCatching { track.pause() }
        runCatching { track.flush() }
        runCatching { track.release() }
    }

    /**
     * Blocking write of [size] bytes from a **direct** [ByteBuffer] into the HAL.
     *
     * This is the zero-copy hot path. [buffer] must be a direct `ByteBuffer`
     * whose position is 0 and whose limit is at least [size]. The underlying
     * `AudioTrack.write(ByteBuffer, int, int)` call is valid for every PCM
     * encoding — including `ENCODING_PCM_FLOAT` which rejects the byte-array
     * overload with `ERROR_INVALID_OPERATION`.
     *
     * Implements write-loop retry semantics: partial writes are re-issued
     * until the whole chunk is accepted or the track is released underneath
     * us. Returns the total bytes actually written or a negative AudioTrack
     * error code.
     *
     * @param buffer Direct `ByteBuffer` filled by the native decoder; position
     *   must be 0, limit must be ≥ [size].
     * @param size Number of valid bytes starting at position 0.
     * @return Total bytes written, or a negative [AudioTrack] error code.
     */
    override fun write(buffer: ByteBuffer, size: Int): Int {
        var offset = 0
        var remaining = size
        while (remaining > 0) {
            buffer.limit(offset + remaining)
            buffer.position(offset)
            val written = track.write(buffer, remaining, AudioTrack.WRITE_BLOCKING)
            if (written < 0) return written
            if (written == 0) return offset
            offset += written
            remaining -= written
        }
        return offset
    }

    /**
     * Blocking write of [size] bytes from [buffer] into the HAL.
     *
     * Routes through `AudioTrack.write(ByteBuffer, int, int)` so the call is
     * valid for every PCM encoding the sink may have negotiated — the
     * `byte[]` overload is rejected by the platform for `ENCODING_PCM_FLOAT`
     * with `ERROR_INVALID_OPERATION` (-3), which would stall the engine the
     * moment a float-direct path is chosen.
     *
     * Implements write-loop retry semantics: partial writes are re-issued
     * until the whole chunk is accepted or the track is released underneath
     * us. Returns the total number of bytes actually written, or a negative
     * AudioTrack error code when the HAL gave up.
     *
     * The underlying sample byte order is the platform's native order — the
     * FFmpeg bridge already produces little-endian PCM on every ABI we
     * support, matching what the HAL expects.
     */
    fun write(buffer: ByteArray, size: Int): Int {
        val view = viewFor(buffer)
        var offset = 0
        var remaining = size
        while (remaining > 0) {
            view.limit(offset + remaining)
            view.position(offset)
            val written = track.write(view, remaining, AudioTrack.WRITE_BLOCKING)
            if (written < 0) {
                // Surface the negative AudioTrack error to the caller.
                return written
            }
            if (written == 0) {
                // HAL is paused / not initialised — bail rather than spin.
                return offset
            }
            offset += written
            remaining -= written
        }
        return offset
    }

    /**
     * Returns a `ByteBuffer` view over [buffer], reusing the cached wrapper
     * when the underlying array has not changed.
     *
     * `ByteBuffer.wrap` is a zero-copy view, but allocating a fresh one on
     * every write would still churn the young generation on the audio thread.
     * Since the owning engine reuses the same PCM scratch array across
     * iterations, we keep one view per array identity for the lifetime of the
     * sink.
     */
    private fun viewFor(buffer: ByteArray): ByteBuffer {
        val cached = writeView
        if (cached != null && cached.hasArray() && cached.array() === buffer) {
            return cached
        }
        val fresh = ByteBuffer.wrap(buffer).order(ByteOrder.nativeOrder())
        writeView = fresh
        return fresh
    }

    /** @return Current playback-head position in frames since the last flush. */
    override fun getPlaybackHeadPositionFrames(): Long =
        track.playbackHeadPosition.toLong() and 0xFFFFFFFFL

    companion object {
        private const val TAG = "AudioTrackSink"

        /**
         * Four-times `minBufferSize` — large enough for lossless playback to keep
         * the HAL fed across deep-sleep CPU transitions while remaining small enough
         * for sub-second pause response.
         *
         * Using 4× (instead of the previous 3×) also keeps the AudioTrack off the
         * FastMixer / low-latency path on most OEM HALs, complementing the explicit
         * [AudioTrack.PERFORMANCE_MODE_NONE] setting and enabling the Deep Buffer
         * route that allows longer CPU-sleep cycles between wake-ups.
         */
        const val DEFAULT_BUFFER_MULTIPLIER: Int = 4
    }
}
