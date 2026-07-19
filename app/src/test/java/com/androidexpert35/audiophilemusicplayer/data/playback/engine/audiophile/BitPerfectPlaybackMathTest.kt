package com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies playback-position anchoring across seek and sink rebuild boundaries.
 */
class BitPerfectPlaybackMathTest {

    @Test
    fun `given absolute libusb head after seek when anchored then target is not added twice`() {
        val dsdRateHz = 2_822_400
        val targetMs = 60_000L
        val seekHeadFrames = targetMs * dsdRateHz / 1_000L

        val positionMs = calculateBitPerfectPlaybackPositionMs(
            playStartPositionMs = targetMs,
            sinkStartFrames = seekHeadFrames,
            playbackHeadFrames = seekHeadFrames,
            sampleRateHz = dsdRateHz,
        )

        assertEquals(targetMs, positionMs)
    }

    @Test
    fun `given anchored DSD seek when one second renders then position advances one second`() {
        val dsdRateHz = 5_644_800
        val targetMs = 90_000L
        val seekHeadFrames = targetMs * dsdRateHz / 1_000L

        val positionMs = calculateBitPerfectPlaybackPositionMs(
            playStartPositionMs = targetMs,
            sinkStartFrames = seekHeadFrames,
            playbackHeadFrames = seekHeadFrames + dsdRateHz,
            sampleRateHz = dsdRateHz,
        )

        assertEquals(91_000L, positionMs)
    }
}
