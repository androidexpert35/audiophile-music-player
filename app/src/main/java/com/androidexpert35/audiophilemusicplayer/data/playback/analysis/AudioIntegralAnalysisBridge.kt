@file:Suppress("JniMissingFunction")

package com.androidexpert35.audiophilemusicplayer.data.playback.analysis

import android.util.Log
import com.androidexpert35.audiophilemusicplayer.data.playback.analysis.AudioIntegralAnalysisBridge.Companion.FEATURE_COUNT
import java.nio.ByteBuffer

/**
 * Measures the loudness, peak and clipping of a whole decoded stream without touching
 * playback.
 *
 * The integral counterpart of [AudioAnalysisBridge], sharing its native session
 * machinery in `audio_analysis_bridge.cpp` but running a different graph
 * (`aformat` → `astats` → `ebur128`). Nothing here modifies samples, and nothing here is
 * part of the bit-perfect output path: the caller decodes its own copy of the audio on
 * `@IoDispatcher` and throws it away afterwards.
 *
 * ### Why every window matters
 *
 * The values this bridge returns are properties of the *whole* stream, so unlike the
 * stationary pass it must be fed the complete decode in order. Feeding it a sample of a
 * track produces a confidently wrong peak, which is worse than no peak at all — a gain
 * stage that trusts an underestimate clips. There is no partial-result mode on purpose.
 *
 * ### Ownership & threading
 *
 * Each instance owns exactly one native session (an opaque `jlong` handle). The native
 * layer performs **no internal locking** — every method on a given instance MUST be
 * called from the same thread, and that thread must never be
 * `BitPerfectPlaybackEngine`'s `THREAD_PRIORITY_AUDIO` HandlerThread. A full-file pass is
 * the most expensive background work in the app; the audio thread has no slack for any
 * of it.
 *
 * ### Lifecycle
 *
 * ```
 *   val bridge = AudioIntegralAnalysisBridge()
 *   if (bridge.open(sampleRateHz, channelCount, encoding)) {
 *       while (moreAudio) {
 *           bridge.feed(directBuffer, frames)      // the whole stream, in order
 *       }
 *       val features = bridge.readFeatures()       // finalises the session
 *   }
 *   bridge.close()
 * ```
 *
 * [readFeatures] flushes the graph, so it ends the feeding phase: a [feed] after it is
 * rejected. [close] is idempotent and must be called on error paths too.
 *
 * When FFmpeg is not provisioned (stub build) [open] returns `false` and the caller
 * simply records the track as not analysable — no crash, no exception, and above all no
 * invented loudness figure.
 */
class AudioIntegralAnalysisBridge {

    /** Native session handle, or `0` before [open] and after [close]. */
    private var handle: Long = 0L

    /**
     * Opens a measurement session for PCM of the given shape.
     *
     * @param sampleRateHz Sample rate of the PCM that will be fed, in Hz.
     * @param channelCount Interleaved channel count of that PCM.
     * @param inputEncoding `AudioFormat.ENCODING_PCM_16BIT`, `ENCODING_PCM_FLOAT`
     *   or `ENCODING_PCM_32BIT` — the encoding the decoder reports for the source.
     * @return `true` when the graph is ready to receive audio; `false` when the
     *   parameters are unusable or FFmpeg is absent from this build, in which case the
     *   reason has already been logged.
     * @throws IllegalStateException if a session is already open on this instance.
     */
    fun open(sampleRateHz: Int, channelCount: Int, inputEncoding: Int): Boolean {
        check(handle == 0L) { "AudioIntegralAnalysisBridge already open" }
        val opened = nativeOpen(sampleRateHz, channelCount, inputEncoding)
        if (opened == 0L) {
            val reason = nativeConsumeLastInitError().ifBlank { "no detail captured" }
            Log.w(
                TAG,
                "Integral session refused for rate=${sampleRateHz}Hz ch=$channelCount " +
                    "enc=$inputEncoding: $reason"
            )
            return false
        }
        handle = opened
        return true
    }

    /**
     * Feeds one contiguous block of interleaved PCM into the measurement graph.
     *
     * Blocks must arrive in stream order and without gaps; a skipped block silently
     * corrupts the loudness gating and the flat-top run lengths.
     *
     * @param buffer Direct [ByteBuffer] holding the block at position 0. The native layer
     *   reads it through `GetDirectBufferAddress` and copies out of it, so the caller may
     *   refill it as soon as this call returns.
     * @param frames Frames to consume (NOT a byte count). The buffer must hold at least
     *   `frames × channelCount × bytesPerSample` bytes.
     * @return `true` when the block was accepted; `false` on a rejected argument, a
     *   filter error, or a call made after [readFeatures].
     * @throws IllegalStateException when called before [open] or after [close].
     * @throws IllegalArgumentException when [buffer] is not direct.
     */
    fun feed(buffer: ByteBuffer, frames: Int): Boolean {
        val h = handle
        check(h != 0L) { "AudioIntegralAnalysisBridge used before open() or after close()" }
        require(buffer.isDirect) {
            "AudioIntegralAnalysisBridge.feed requires a direct ByteBuffer"
        }
        val consumed = nativeFeed(h, buffer, frames)
        if (consumed < 0) {
            Log.w(TAG, "Integral block rejected (frames=$frames, code=$consumed)")
            return false
        }
        return true
    }

    /**
     * Flushes the graph and returns the aggregate over everything fed so far.
     *
     * Finalises the session: the graph is sent end-of-stream so `ebur128` releases its
     * last gating block and any flat-top run still open is closed, after which [feed] no
     * longer accepts data. Calling this twice returns the same aggregate.
     *
     * @return The measured features, or `null` when the native layer could not produce
     *   them.
     * @throws IllegalStateException when called before [open] or after [close].
     */
    fun readFeatures(): AudioIntegralFeatures? {
        val h = handle
        check(h != 0L) { "AudioIntegralAnalysisBridge used before open() or after close()" }
        val values = DoubleArray(FEATURE_COUNT)
        val written = nativeReadFeatures(h, values)
        if (written != FEATURE_COUNT) {
            Log.w(TAG, "Integral aggregate unavailable (code=$written)")
            return null
        }
        return AudioIntegralFeatures(
            samplePeakDbfs = values[INDEX_SAMPLE_PEAK_DBFS].measuredOrNull(),
            truePeakDbfs = values[INDEX_TRUE_PEAK_DBFS].measuredOrNull(),
            integratedLufs = values[INDEX_INTEGRATED_LUFS].measuredOrNull(),
            plrDb = values[INDEX_PLR_DB].measuredOrNull(),
            clippingRatio = values[INDEX_CLIPPING_RATIO].measuredOrNull(),
            flatRunCount = values[INDEX_FLAT_RUN_COUNT].toLong(),
            flatRunLongestSamples = values[INDEX_FLAT_RUN_LONGEST].measuredOrNull(),
            flatRunMeanSamples = values[INDEX_FLAT_RUN_MEAN].measuredOrNull(),
            flatRunSampleRatio = values[INDEX_FLAT_RUN_SAMPLE_RATIO].measuredOrNull(),
            frameCount = values[INDEX_FRAME_COUNT].toLong(),
        )
    }

    /**
     * Releases the native session. Safe to call multiple times; subsequent operations on
     * this instance throw [IllegalStateException].
     */
    fun close() {
        val h = handle
        if (h != 0L) {
            handle = 0L
            nativeClose(h)
        }
    }

    companion object {

        private const val TAG = "AudioIntegralAnalysis"

        // ── Feature vector layout ────────────────────────────────────────────
        //
        // Wire contract with the AudioIntegralFeatureIndex enum in
        // app/src/main/cpp/audio_integral_aggregator.h — keep the two in sync.
        // The native side writes NaN into every slot it could not measure.
        private const val INDEX_SAMPLE_PEAK_DBFS = 0
        private const val INDEX_TRUE_PEAK_DBFS = 1
        private const val INDEX_INTEGRATED_LUFS = 2
        private const val INDEX_PLR_DB = 3
        private const val INDEX_CLIPPING_RATIO = 4
        private const val INDEX_FLAT_RUN_COUNT = 5
        private const val INDEX_FLAT_RUN_LONGEST = 6
        private const val INDEX_FLAT_RUN_MEAN = 7
        private const val INDEX_FLAT_RUN_SAMPLE_RATIO = 8
        private const val INDEX_FRAME_COUNT = 9
        private const val FEATURE_COUNT = 10

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
        private external fun nativeOpen(
            sampleRateHz: Int,
            channelCount: Int,
            inputEncoding: Int
        ): Long

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
