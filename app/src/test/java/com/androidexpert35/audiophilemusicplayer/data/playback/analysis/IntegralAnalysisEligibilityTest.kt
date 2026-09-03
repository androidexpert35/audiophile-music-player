package com.androidexpert35.audiophilemusicplayer.data.playback.analysis

import com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile.shouldUseHiResRemasterStage
import com.androidexpert35.audiophilemusicplayer.data.playback.native_.AudioFormatInfo
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Tests that the offline loudness pass is spent on exactly the audio that can use it.
 *
 * The point of these is not that the predicate returns particular booleans — it is that
 * it returns the *same* booleans as the Hi-Res Remaster gate. A measurement taken for a
 * track no stage will ever read is wasted battery, and a track that will be remastered
 * with no measurement cached is the case the whole feature exists to fix. So the decisive
 * assertion compares the two predicates directly rather than restating a truth table.
 */
class IntegralAnalysisEligibilityTest {

    @Test
    fun `given every format class then eligibility matches the hi-res remaster gate`() {
        val formats = listOf(
            "lossless CD" to format(),
            "lossless 24-bit" to format(sourceBitDepth = 24),
            "lossless 96 kHz" to format(sampleRateHz = 96_000),
            "lossless 48 kHz" to format(sampleRateHz = 48_000),
            "lossy mp3" to format(codec = AudioCodec.MP3, codecName = "mp3", bitrateKbps = 320),
            "lossy aac" to format(codec = AudioCodec.AAC, codecName = "aac", bitrateKbps = 256),
        )

        formats.forEach { (label, format) ->
            assertEquals(
                "Eligibility diverged from the Hi-Res gate for $label",
                // The gate's own toggle is held on: the toggle says what to do with a
                // track now, eligibility says whether measuring it could ever pay off.
                shouldUseHiResRemasterStage(format, hiResEnabled = true),
                isEligibleForIntegralAnalysis(format),
            )
        }
    }

    @Test
    fun `given a DSD source when checked then it is never eligible`() {
        // DSD is carried to the DAC untouched on every bit-perfect transport, so no DSP
        // decision would ever read a measurement of it.
        assertFalse(isEligibleForIntegralAnalysis(format(isDsd = true)))
        assertFalse(isEligibleForIntegralAnalysis(format(isResampledDsd = true)))
    }

    /**
     * @return A decoded-format descriptor defaulting to a 16/44.1 FLAC — the one class
     *   this pass exists to measure.
     */
    private fun format(
        sampleRateHz: Int = 44_100,
        sourceBitDepth: Int = 16,
        codec: AudioCodec = AudioCodec.FLAC,
        codecName: String = "flac",
        bitrateKbps: Int = 900,
        isDsd: Boolean = false,
        isResampledDsd: Boolean = false,
    ) = AudioFormatInfo(
        sampleRateHz = sampleRateHz,
        channelCount = 2,
        sourceBitDepth = sourceBitDepth,
        androidPcmEncoding = 2,
        bytesPerSample = 2,
        durationMs = 240_000L,
        bitrateKbps = bitrateKbps,
        codec = codec,
        codecName = codecName,
        isDsd = isDsd,
        isResampledDsd = isResampledDsd,
    )
}
