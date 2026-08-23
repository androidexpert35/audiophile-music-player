package com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile

import android.media.AudioFormat
import com.androidexpert35.audiophilemusicplayer.data.playback.native_.AudioFormatInfo
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioCodec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies decoder-ownership constraints for sink-preserving track transitions.
 */
class BitPerfectGaplessQueueTest {

    @Test
    fun `given matching PCM tracks on engine-fed sink when checked then decoder swap is allowed`() {
        val current = pcmFormat()
        val next = pcmFormat(durationMs = 240_000L)

        val compatible = BitPerfectGaplessQueue.canSwapDecoderInPlace(
            current = current,
            next = next,
            sinkUsesNativeDecoderPump = false,
        )

        assertTrue(compatible)
    }

    @Test
    fun `given matching PCM tracks on native decoder-pump sink when checked then decoder swap is rejected`() {
        val current = pcmFormat()
        val next = pcmFormat(durationMs = 240_000L)

        val compatible = BitPerfectGaplessQueue.canSwapDecoderInPlace(
            current = current,
            next = next,
            sinkUsesNativeDecoderPump = true,
        )

        assertFalse(compatible)
    }

    private fun pcmFormat(durationMs: Long = 180_000L): AudioFormatInfo = AudioFormatInfo(
        sampleRateHz = 96_000,
        channelCount = 2,
        sourceBitDepth = 24,
        androidPcmEncoding = AudioFormat.ENCODING_PCM_24BIT_PACKED,
        bytesPerSample = 3,
        durationMs = durationMs,
        bitrateKbps = 2_800,
        codec = AudioCodec.FLAC,
    )
}
