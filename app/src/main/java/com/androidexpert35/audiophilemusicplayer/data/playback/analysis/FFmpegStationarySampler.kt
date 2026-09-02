package com.androidexpert35.audiophilemusicplayer.data.playback.analysis

import android.util.Log
import com.androidexpert35.audiophilemusicplayer.data.playback.analysis.FFmpegStationarySampler.Companion.NO_SEEK
import com.androidexpert35.audiophilemusicplayer.data.playback.native_.AudioFormatInfo
import com.androidexpert35.audiophilemusicplayer.data.playback.native_.FFmpegDecoder
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * Measures a source file by decoding a few short windows of it and nothing else.
 *
 * The pass builds its **own** [FFmpegDecoder] session. That is safe and deliberate: a
 * decoder instance is one native session owned by one thread ([FFmpegDecoder]), it is not
 * a singleton, and the gapless queue already runs two of them side by side. Reaching for
 * the playback engine's decoder instead would put analysis work on the audio thread,
 * which has no slack for it.
 *
 * The decoder is opened with `forcePcm = true` so that whatever the container holds is
 * presented as plain PCM; the measurement graph has no interest in transport formats. A
 * source that turns out to be DSD is abandoned immediately rather than measured through
 * the resampled fallback, because a DSD track never reaches the DSP stage that would read
 * the numbers.
 *
 * Sampling a handful of windows rather than the whole file is what makes this cheap
 * enough to run over a library, and it is legitimate only for stationary features — the
 * ones that describe the source rather than a moment of it. Integral measures (peak,
 * integrated loudness, clipping) are explicitly not produced here; they need the whole
 * stream and are a different pass.
 *
 * Nothing in this class writes samples anywhere. It changes no DSP behaviour and touches
 * no sink, no telemetry and no engine state.
 */
@Singleton
class FFmpegStationarySampler @Inject constructor() : StationarySampler {

    /**
     * @see StationarySampler.sample
     */
    override fun sample(sourcePath: String): StationarySamplingResult {
        val decoder = FFmpegDecoder()
        var bridge: AudioAnalysisBridge? = null
        return try {
            val format = decoder.open(sourcePath, forcePcm = true)
            when {
                // The decoder is the authority on this, not the scan-time MIME type.
                format.isDsd || format.isResampledDsd -> StationarySamplingResult.DsdSource

                format.sampleRateHz <= 0 || format.bytesPerFrame <= 0 -> {
                    Log.w(
                        TAG,
                        "Refusing to measure an unusable PCM shape: " +
                            "rate=${format.sampleRateHz}Hz bytesPerFrame=${format.bytesPerFrame}"
                    )
                    StationarySamplingResult.Unavailable
                }

                else -> {
                    val session = AudioAnalysisBridge()
                    bridge = session
                    val opened = session.open(
                        sampleRateHz = format.sampleRateHz,
                        channelCount = format.channelCount,
                        inputEncoding = format.androidPcmEncoding,
                    )
                    if (!opened) {
                        // Stub build, or a PCM shape the graph will not accept. Either
                        // way there is nothing to measure with — not a failure.
                        StationarySamplingResult.Unavailable
                    } else {
                        feedWindows(decoder, session, format)
                        session.readFeatures()
                            ?.let(StationarySamplingResult::Measured)
                            ?: StationarySamplingResult.Unavailable
                    }
                }
            }
        } catch (failure: Exception) {
            // A source that will not open or decode is an ordinary outcome for a
            // background sweep over a user's library — it must never propagate.
            StationarySamplingResult.Failed(failure)
        } finally {
            bridge?.close()
            decoder.close()
        }
    }

    /**
     * Decodes the sampled windows and hands each one to the measurement graph.
     *
     * @param decoder Open decoder session for the source being measured.
     * @param bridge Open measurement session that receives the windows.
     * @param format Decoded shape reported by [decoder], fixed for its lifetime.
     */
    private fun feedWindows(
        decoder: FFmpegDecoder,
        bridge: AudioAnalysisBridge,
        format: AudioFormatInfo,
    ) {
        val windowFrames = (
            format.sampleRateHz.toLong() * WINDOW_DURATION_MS / MILLIS_PER_SECOND
            ).toInt().coerceAtLeast(1)
        val window = ByteBuffer.allocateDirect(windowFrames * format.bytesPerFrame)

        for (positionMs in windowPositionsMs(format.durationMs)) {
            if (positionMs != NO_SEEK && !decoder.seekTo(positionMs)) {
                Log.w(TAG, "Seek to ${positionMs}ms failed — measuring from the current position")
            }
            val filledBytes = fillWindow(decoder, window)
            if (filledBytes == 0) {
                // End of stream, or nothing but unrecoverable reads. Later windows sit
                // further into the file and would fare no better.
                break
            }
            bridge.feed(window, filledBytes / format.bytesPerFrame)
        }
    }

    /**
     * Reads until [window] is full, the stream ends, or the decoder keeps stumbling.
     *
     * Each read is aimed at the unfilled tail of [window] through a slice, so a window is
     * assembled from however many chunks the decoder happens to produce and reaches the
     * graph as one contiguous measurement window.
     *
     * @param decoder Open decoder session positioned at the start of the window.
     * @param window Direct buffer to fill; left at position 0 on return.
     * @return Bytes written into [window], `0` when nothing could be read.
     */
    private fun fillWindow(decoder: FFmpegDecoder, window: ByteBuffer): Int {
        var filledBytes = 0
        var recoverableErrors = 0
        while (filledBytes < window.capacity()) {
            window.position(filledBytes)
            val read = decoder.readNextBuffer(window.slice())
            when {
                read > 0 -> filledBytes += read
                read == FFmpegDecoder.READ_EOF -> break
                else -> if (++recoverableErrors > MAX_RECOVERABLE_READ_ERRORS) break
            }
        }
        window.clear()
        return filledBytes
    }

    /**
     * Places the sample windows across the stream.
     *
     * Both ends of a track are avoided: fades, count-ins and applause are not what the
     * source sounds like, and a spectral cutoff measured over a fade-in is a measurement
     * of the fade. The guard is capped at a fraction of the duration so a short track
     * still yields windows instead of an empty span.
     *
     * @param durationMs Stream duration, or `0` when the container declares none.
     * @return Seek targets in milliseconds, or [NO_SEEK] entries when the duration is
     *   unknown and the windows can only be taken consecutively from the start.
     */
    private fun windowPositionsMs(durationMs: Long): List<Long> {
        if (durationMs <= 0L) return List(WINDOW_COUNT) { NO_SEEK }

        val guardMs = min(EDGE_GUARD_MS, durationMs / EDGE_GUARD_DIVISOR)
        val usableMs = durationMs - 2 * guardMs
        if (usableMs <= WINDOW_DURATION_MS) return listOf(guardMs)

        val spanMs = usableMs - WINDOW_DURATION_MS
        return (0 until WINDOW_COUNT).map { index ->
            guardMs + spanMs * index / (WINDOW_COUNT - 1)
        }
    }

    private companion object {

        const val TAG = "TrackSignalAnalysis"

        /**
         * Windows taken per track. Inside the 3–5 the feature design calls for: enough
         * for a verse/chorus difference to average out, few enough that a sweep over a
         * whole library stays cheap.
         */
        const val WINDOW_COUNT = 4

        /** Length of one sampled window. Comfortably more than one FFT frame. */
        const val WINDOW_DURATION_MS = 500L

        /** Longest stretch skipped at each end of a track. */
        const val EDGE_GUARD_MS = 3_000L

        /** Caps the edge guard at a tenth of the duration so short tracks stay samplable. */
        const val EDGE_GUARD_DIVISOR = 10L

        /** Position sentinel meaning "read on from wherever the decoder already is". */
        const val NO_SEEK = -1L

        /** Recoverable decode blips tolerated while filling one window before giving up. */
        const val MAX_RECOVERABLE_READ_ERRORS = 3

        const val MILLIS_PER_SECOND = 1_000L
    }
}
