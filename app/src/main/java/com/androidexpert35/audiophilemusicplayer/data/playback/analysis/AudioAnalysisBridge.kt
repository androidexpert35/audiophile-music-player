@file:Suppress("JniMissingFunction")

package com.androidexpert35.audiophilemusicplayer.data.playback.analysis

import android.util.Log
import com.androidexpert35.audiophilemusicplayer.data.playback.analysis.AudioAnalysisBridge.Companion.FEATURE_COUNT
import java.nio.ByteBuffer

/**
 * Measures the stationary signal properties of decoded PCM without touching playback.
 *
 * Wraps `audio_analysis_bridge.cpp`, which runs a measurement-only libavfilter
 * graph (`aformat` → `aspectralstats` → `astats` → packed-FLT `aformat`) over
 * the windows it is fed and returns the aggregate. Nothing here modifies samples,
 * and nothing here is part of the bit-perfect output path: the caller decodes its
 * own copy of the audio on `@IoDispatcher` and throws it away afterwards.
 *
 * ### Ownership & threading
 *
 * Each instance owns exactly one native session (an opaque `jlong` handle). The
 * native layer performs **no internal locking** — every method on a given
 * instance MUST be called from the same thread, and that thread must never be
 * `BitPerfectPlaybackEngine`'s `THREAD_PRIORITY_AUDIO` HandlerThread. Analysis
 * is background work; the audio thread has no slack to spare for it.
 *
 * ### Lifecycle
 *
 * ```
 *   val bridge = AudioAnalysisBridge()
 *   if (bridge.open(sampleRateHz, channelCount, encoding)) {
 *       while (moreWindows) {
 *           bridge.feed(directBuffer, frames)      // N windows
 *       }
 *       val features = bridge.readFeatures()       // finalises the session
 *   }
 *   bridge.close()
 * ```
 *
 * [readFeatures] flushes the graph, so it ends the feeding phase: a [feed] after
 * it is rejected. [close] is idempotent and must be called on error paths too.
 *
 * When FFmpeg is not provisioned (stub build) [open] returns `false` and the
 * caller simply records the track as not analysable — no crash, no exception.
 */
class AudioAnalysisBridge {

    /** Native session handle, or `0` before [open] and after [close]. */
    private var handle: Long = 0L

    /**
     * Opens a measurement session for PCM of the given shape.
     *
     * @param sampleRateHz Sample rate of the PCM that will be fed, in Hz.
     * @param channelCount Interleaved channel count of that PCM.
     * @param inputEncoding `AudioFormat.ENCODING_PCM_16BIT`, `ENCODING_PCM_FLOAT`
     *   or `ENCODING_PCM_32BIT` — the encoding the decoder reports for the source.
     * @return `true` when the graph is ready to receive windows; `false` when the
     *   parameters are unusable or FFmpeg is absent from this build, in which case
     *   the reason has already been logged.
     * @throws IllegalStateException if a session is already open on this instance.
     */
    fun open(sampleRateHz: Int, channelCount: Int, inputEncoding: Int): Boolean {
        check(handle == 0L) { "AudioAnalysisBridge already open" }
        val opened = nativeOpen(sampleRateHz, channelCount, inputEncoding)
        if (opened == 0L) {
            val reason = nativeConsumeLastInitError().ifBlank { "no detail captured" }
            Log.w(
                TAG,
                "Analysis session refused for rate=${sampleRateHz}Hz ch=$channelCount " +
                    "enc=$inputEncoding: $reason"
            )
            return false
        }
        handle = opened
        return true
    }

    /**
     * Feeds one window of interleaved PCM into the measurement graph.
     *
     * @param buffer Direct [ByteBuffer] holding the window at position 0. The
     *   native layer reads it through `GetDirectBufferAddress` and copies out of
     *   it, so the caller may refill it as soon as this call returns.
     * @param frames Frames to consume (NOT a byte count). The buffer must hold at
     *   least `frames × channelCount × bytesPerSample` bytes.
     * @return `true` when the window was accepted; `false` on a rejected argument,
     *   a filter error, or a call made after [readFeatures].
     * @throws IllegalStateException when called before [open] or after [close].
     * @throws IllegalArgumentException when [buffer] is not direct.
     */
    fun feed(buffer: ByteBuffer, frames: Int): Boolean {
        val h = handle
        check(h != 0L) { "AudioAnalysisBridge used before open() or after close()" }
        require(buffer.isDirect) { "AudioAnalysisBridge.feed requires a direct ByteBuffer" }
        val consumed = nativeFeed(h, buffer, frames)
        if (consumed < 0) {
            Log.w(TAG, "Analysis window rejected (frames=$frames, code=$consumed)")
            return false
        }
        return true
    }

    /**
     * Flushes the graph and returns the aggregate over every window fed so far.
     *
     * Finalises the session: the graph is sent end-of-stream so the stats filters
     * release their last partial window, after which [feed] no longer accepts
     * data. Calling this twice returns the same aggregate.
     *
     * @return The measured features, or `null` when the native layer could not
     *   produce them.
     * @throws IllegalStateException when called before [open] or after [close].
     */
    fun readFeatures(): AudioAnalysisFeatures? {
        val h = handle
        check(h != 0L) { "AudioAnalysisBridge used before open() or after close()" }
        val values = DoubleArray(FEATURE_COUNT)
        val written = nativeReadFeatures(h, values)
        if (written != FEATURE_COUNT) {
            Log.w(TAG, "Analysis aggregate unavailable (code=$written)")
            return null
        }
        return AudioAnalysisFeatures(
            spectralRolloffHz = values[INDEX_SPECTRAL_ROLLOFF_HZ].measuredOrNull(),
            spectralCentroidHz = values[INDEX_SPECTRAL_CENTROID_HZ].measuredOrNull(),
            spectralSlope = values[INDEX_SPECTRAL_SLOPE].measuredOrNull(),
            noiseFloorDbfs = values[INDEX_NOISE_FLOOR_DBFS].measuredOrNull(),
            dcOffset = values[INDEX_DC_OFFSET].measuredOrNull(),
            leftRmsDbfs = values[INDEX_LEFT_RMS_DBFS].measuredOrNull(),
            rightRmsDbfs = values[INDEX_RIGHT_RMS_DBFS].measuredOrNull(),
            midRmsDbfs = values[INDEX_MID_RMS_DBFS].measuredOrNull(),
            sideRmsDbfs = values[INDEX_SIDE_RMS_DBFS].measuredOrNull(),
            interChannelCorrelation = values[INDEX_INTER_CHANNEL_CORRELATION].measuredOrNull(),
            windowCount = values[INDEX_WINDOW_COUNT].toInt(),
            frameCount = values[INDEX_FRAME_COUNT].toLong(),
        )
    }

    /**
     * Releases the native session. Safe to call multiple times; subsequent
     * operations on this instance throw [IllegalStateException].
     */
    fun close() {
        val h = handle
        if (h != 0L) {
            handle = 0L
            nativeClose(h)
        }
    }

    companion object {

        private const val TAG = "AudioAnalysisBridge"

        // ── Feature vector layout ────────────────────────────────────────────
        //
        // Wire contract with the AudioAnalysisFeatureIndex enum in
        // app/src/main/cpp/audio_analysis_aggregator.h — keep the two in sync.
        // The native side writes NaN into every slot it could not measure.
        private const val INDEX_SPECTRAL_ROLLOFF_HZ = 0
        private const val INDEX_SPECTRAL_CENTROID_HZ = 1
        private const val INDEX_SPECTRAL_SLOPE = 2
        private const val INDEX_NOISE_FLOOR_DBFS = 3
        private const val INDEX_DC_OFFSET = 4
        private const val INDEX_LEFT_RMS_DBFS = 5
        private const val INDEX_RIGHT_RMS_DBFS = 6
        private const val INDEX_MID_RMS_DBFS = 7
        private const val INDEX_SIDE_RMS_DBFS = 8
        private const val INDEX_INTER_CHANNEL_CORRELATION = 9
        private const val INDEX_WINDOW_COUNT = 10
        private const val INDEX_FRAME_COUNT = 11
        private const val FEATURE_COUNT = 12

        /** NaN is the native "not measured" marker; everything else is a reading. */
        private fun Double.measuredOrNull(): Double? = if (isNaN()) null else this

        init {
            // audiophile_native is normally already loaded by FFmpegDecoder; a
            // second System.loadLibrary call is a documented no-op when the
            // library name matches an already-loaded DSO.
            runCatching { System.loadLibrary("audiophile_native") }
                .onFailure {
                    Log.e(TAG, "Failed to load audiophile_native — analysis bridge inactive", it)
                }
        }

        @JvmStatic
        private external fun nativeOpen(sampleRateHz: Int, channelCount: Int, inputEncoding: Int): Long

        @JvmStatic
        private external fun nativeConsumeLastInitError(): String

        /** @param buffer MUST be a direct [ByteBuffer]; the C layer reads its backing memory. */
        @JvmStatic
        private external fun nativeFeed(handle: Long, buffer: ByteBuffer, frames: Int): Int

        /** @param outValues Receives [FEATURE_COUNT] doubles; NaN marks an unmeasured slot. */
        @JvmStatic
        private external fun nativeReadFeatures(handle: Long, outValues: DoubleArray): Int

        @JvmStatic
        private external fun nativeClose(handle: Long)
    }
}
