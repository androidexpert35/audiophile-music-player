package com.androidexpert35.audiophilemusicplayer.data.playback.analysis

import android.os.SystemClock
import android.util.Log
import com.androidexpert35.audiophilemusicplayer.data.playback.native_.AudioFormatInfo
import com.androidexpert35.audiophilemusicplayer.data.playback.native_.FFmpegDecoder
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Measures a source file by decoding all of it and nothing less.
 *
 * The pass builds its **own** [FFmpegDecoder] session. That is safe and deliberate: a
 * decoder instance is one native session owned by one thread ([FFmpegDecoder]), it is not
 * a singleton, and the gapless queue already runs two of them side by side. Reaching for
 * the playback engine's decoder instead would put analysis work on the audio thread,
 * which has no slack for it.
 *
 * The decoder is opened with `forcePcm = true` so whatever the container holds is
 * presented as plain PCM. A source that turns out to be DSD is abandoned immediately
 * rather than measured through the resampled fallback: DSD never reaches the DSP stage
 * that would read the numbers, and the fallback's own resampling would be what got
 * measured.
 *
 * ### Cost
 *
 * Unlike the stationary pass this one is not cheap — it decodes the entire file — so
 * every pass reports the wall-clock time it took and the number of frames it covered,
 * both in [IntegralSamplingResult.Measured] and in a log line. A scheduler deciding when
 * to sweep a library needs the real figure for the device it is running on, not an
 * estimate, and that figure differs by an order of magnitude between a 16/44.1 FLAC and a
 * 24/96 one.
 *
 * Nothing in this class writes samples anywhere. It changes no DSP behaviour and touches
 * no sink, no telemetry and no engine state.
 */
@Singleton
class FFmpegIntegralSampler @Inject constructor() : IntegralSampler {

    /**
     * @see IntegralSampler.measure
     */
    override fun measure(sourcePath: String): IntegralSamplingResult {
        val startedAtMillis = SystemClock.elapsedRealtime()
        val decoder = FFmpegDecoder()
        var bridge: AudioIntegralAnalysisBridge? = null
        return try {
            val format = decoder.open(sourcePath, forcePcm = true)
            when {
                // The decoder is the authority on the decoded shape, not the scan
                // metadata: it knows the bit depth, and it is right about DSD.
                !isEligibleForIntegralAnalysis(format) -> IntegralSamplingResult.Ineligible

                format.sampleRateHz <= 0 || format.bytesPerFrame <= 0 -> {
                    Log.w(
                        TAG,
                        "Refusing to measure an unusable PCM shape: " +
                            "rate=${format.sampleRateHz}Hz bytesPerFrame=${format.bytesPerFrame}"
                    )
                    IntegralSamplingResult.Unavailable
                }

                else -> {
                    val session = AudioIntegralAnalysisBridge()
                    bridge = session
                    val opened = session.open(
                        sampleRateHz = format.sampleRateHz,
                        channelCount = format.channelCount,
                        inputEncoding = format.androidPcmEncoding,
                    )
                    if (!opened) {
                        // Stub build, or a PCM shape the graph will not accept. Either
                        // way there is nothing to measure with — not a failure.
                        IntegralSamplingResult.Unavailable
                    } else {
                        val frames = feedWholeStream(decoder, session, format)
                        session.readFeatures()
                            ?.let { features ->
                                report(
                                    format = format,
                                    features = features,
                                    elapsedMillis = SystemClock.elapsedRealtime() - startedAtMillis,
                                    decodedFrames = frames,
                                )
                            }
                            ?: IntegralSamplingResult.Unavailable
                    }
                }
            }
        } catch (failure: Exception) {
            // A source that will not open or decode is an ordinary outcome for a
            // background sweep over a user's library — it must never propagate.
            IntegralSamplingResult.Failed(failure)
        } finally {
            bridge?.close()
            decoder.close()
        }
    }

    /**
     * Decodes [decoder] to the end, handing every block to the measurement graph.
     *
     * No seeking and no sampling: the graph's loudness gating and its flat-top run
     * lengths are only correct over a contiguous stream read in order.
     *
     * @param decoder Open decoder session for the source being measured.
     * @param bridge Open measurement session that receives the audio.
     * @param format Decoded shape reported by [decoder], fixed for its lifetime.
     * @return PCM frames handed to the graph.
     */
    private fun feedWholeStream(
        decoder: FFmpegDecoder,
        bridge: AudioIntegralAnalysisBridge,
        format: AudioFormatInfo,
    ): Long {
        val blockFrames = (
            format.sampleRateHz.toLong() * BLOCK_DURATION_MS / MILLIS_PER_SECOND
            ).toInt().coerceAtLeast(1)
        val block = ByteBuffer.allocateDirect(blockFrames * format.bytesPerFrame)

        var decodedFrames = 0L
        var recoverableErrors = 0
        while (true) {
            block.clear()
            val read = decoder.readNextBuffer(block)
            when {
                read > 0 -> {
                    val frames = read / format.bytesPerFrame
                    if (frames > 0) {
                        block.position(0)
                        if (!bridge.feed(block, frames)) {
                            // The graph rejected a block, so the aggregate no longer
                            // covers a contiguous stream. Stop rather than report a
                            // figure that silently has a hole in it.
                            Log.w(TAG, "Measurement graph rejected a block — pass abandoned")
                            return decodedFrames
                        }
                        decodedFrames += frames
                    }
                    recoverableErrors = 0
                }
                read == FFmpegDecoder.READ_EOF -> return decodedFrames
                else -> if (++recoverableErrors > MAX_RECOVERABLE_READ_ERRORS) {
                    Log.w(TAG, "Giving up after $recoverableErrors consecutive decode errors")
                    return decodedFrames
                }
            }
        }
    }

    /**
     * Logs what the pass cost and wraps the aggregate for the caller.
     *
     * The log line carries the format class alongside the timing because that is the
     * comparison the scheduling decision rests on — a 24/96 file is not a 16/44.1 file
     * with a bigger number attached, it is several times the work per second of audio.
     *
     * @param format Decoded shape that was measured.
     * @param features Aggregate the graph produced.
     * @param elapsedMillis Wall-clock duration of the whole pass.
     * @param decodedFrames PCM frames that reached the graph.
     * @return The measured result.
     */
    private fun report(
        format: AudioFormatInfo,
        features: AudioIntegralFeatures,
        elapsedMillis: Long,
        decodedFrames: Long,
    ): IntegralSamplingResult {
        val audioSeconds = decodedFrames.toDouble() / format.sampleRateHz.coerceAtLeast(1)
        Log.i(
            TAG,
            "Integral pass: ${format.codecName} ${format.sourceBitDepth}bit/" +
                "${format.sampleRateHz}Hz — ${elapsedMillis}ms for " +
                "${"%.1f".format(audioSeconds)}s of audio " +
                "(peak=${features.samplePeakDbfs} dBFS, truePeak=${features.truePeakDbfs} dBFS, " +
                "LUFS=${features.integratedLufs}, PLR=${features.plrDb} dB, " +
                "clipping=${features.clippingRatio}, flatRuns=${features.flatRunCount})"
        )
        return IntegralSamplingResult.Measured(
            features = features,
            elapsedMillis = elapsedMillis,
            decodedFrames = decodedFrames,
        )
    }

    private companion object {

        const val TAG = "TrackIntegralAnalysis"

        /**
         * Length of audio requested per decoder read. Large enough that the per-call
         * JNI and filter-graph overhead is negligible against the decode itself, small
         * enough that the direct buffer stays well under a megabyte even at 24/192.
         */
        const val BLOCK_DURATION_MS = 500L

        /** Recoverable decode blips tolerated in a row before the pass is abandoned. */
        const val MAX_RECOVERABLE_READ_ERRORS = 3

        const val MILLIS_PER_SECOND = 1_000L
    }
}
