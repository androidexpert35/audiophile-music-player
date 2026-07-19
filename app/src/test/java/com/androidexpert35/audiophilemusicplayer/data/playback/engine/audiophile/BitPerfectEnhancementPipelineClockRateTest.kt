package com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile

import com.androidexpert35.audiophilemusicplayer.data.playback.native_.AudioFormatInfo
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioCodec
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.DsdOutputMode
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.DsdRate
import org.junit.Assert.assertEquals
import org.junit.Test

class BitPerfectEnhancementPipelineClockRateTest {

    private val dsdFormat = AudioFormatInfo(
        // FFmpeg reports DSD "sample rate" as the byte rate (bit rate / 8), e.g.
        // 352,800 Hz for DSD64 — this is deliberately NOT the sink's frame clock.
        sampleRateHz = DsdRate.DSD64.sampleRateHz / 8,
        channelCount = 2,
        sourceBitDepth = 1,
        androidPcmEncoding = 0,
        bytesPerSample = 1,
        durationMs = 0L,
        bitrateKbps = 0,
        codec = AudioCodec.DSD_64,
        isDsd = true,
        dsdRate = DsdRate.DSD64,
    )

    @Test
    fun `given native DSD passthrough when resolving playback clock then uses full one-bit DSD rate not the FFmpeg byte rate`() {
        val context = DsdPlaybackContext(
            sourceRate = DsdRate.DSD64,
            effectiveRate = DsdRate.DSD64,
            outputMode = DsdOutputMode.NativeDsd(DsdRate.DSD64),
            sourceFormat = "DSF",
            dopPcmRate = null,
            dopEncoder = null,
        )

        val clockRateHz = pipelinePlaybackClockRate(
            format = dsdFormat,
            dsdPlaybackContext = context,
            sueStage = null,
        )

        // Must match LibusbDsdAudioSink/UsbAudioSink's playhead unit (the full
        // one-bit DSD rate) or the seek bar drifts from real time.
        assertEquals(DsdRate.DSD64.sampleRateHz, clockRateHz)
    }

    @Test
    fun `given DoP transport when resolving playback clock then uses the DoP PCM carrier rate`() {
        val context = DsdPlaybackContext(
            sourceRate = DsdRate.DSD64,
            effectiveRate = DsdRate.DSD64,
            outputMode = DsdOutputMode.DoP(maxPcmRate = 176_400),
            sourceFormat = "DSF",
            dopPcmRate = 176_400,
            dopEncoder = null,
        )

        val clockRateHz = pipelinePlaybackClockRate(
            format = dsdFormat,
            dsdPlaybackContext = context,
            sueStage = null,
        )

        assertEquals(176_400, clockRateHz)
    }

    @Test
    fun `given libusb DSD playhead when transport is DoP then sink one-bit clock wins`() {
        val context = DsdPlaybackContext(
            sourceRate = DsdRate.DSD64,
            effectiveRate = DsdRate.DSD64,
            outputMode = DsdOutputMode.DoP(maxPcmRate = 176_400),
            sourceFormat = "DSF",
            dopPcmRate = 176_400,
            dopEncoder = null,
        )

        val clockRateHz = pipelinePlaybackClockRate(
            format = dsdFormat,
            dsdPlaybackContext = context,
            sueStage = null,
            sinkClockRateHz = DsdRate.DSD64.sampleRateHz,
        )

        assertEquals(DsdRate.DSD64.sampleRateHz, clockRateHz)
    }

    @Test
    fun `given plain PCM passthrough when resolving playback clock then uses the source sample rate`() {
        val pcmFormat = dsdFormat.copy(sampleRateHz = 44_100, isDsd = false, dsdRate = null)

        val clockRateHz = pipelinePlaybackClockRate(
            format = pcmFormat,
            dsdPlaybackContext = null,
            sueStage = null,
        )

        assertEquals(44_100, clockRateHz)
    }
}
