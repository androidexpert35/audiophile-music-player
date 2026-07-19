package com.androidexpert35.audiophilemusicplayer.data.playback.engine

import android.media.AudioFormat
import androidx.media3.common.C
import androidx.media3.common.Format
import com.androidexpert35.audiophilemusicplayer.data.playback.engine.standard.createStandardPathReport
import com.androidexpert35.audiophilemusicplayer.data.playback.engine.standard.toStandardAudioFormatInfo
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Unit tests for the Standard-engine telemetry mappers.
 *
 * Verifies that Media3 audio formats are translated into the shared telemetry
 * model so the collector can keep showing useful data after an engine switch.
 */
class StandardEngineTelemetryMapperTest {

    @Test
    fun `given media3 audio format when mapped then shared telemetry fields are populated`() {
        val format = Format.Builder()
            .setSampleMimeType("audio/flac")
            .setChannelCount(2)
            .setSampleRate(96_000)
            .setPcmEncoding(C.ENCODING_PCM_24BIT)
            .setAverageBitrate(2_304_000)
            .build()

        val telemetryFormat = format.toStandardAudioFormatInfo()

        assertEquals(96_000, telemetryFormat.sampleRateHz)
        assertEquals(2, telemetryFormat.channelCount)
        assertEquals(24, telemetryFormat.sourceBitDepth)
        assertEquals(AudioFormat.ENCODING_PCM_24BIT_PACKED, telemetryFormat.androidPcmEncoding)
        assertEquals(3, telemetryFormat.bytesPerSample)
        assertEquals(2_304, telemetryFormat.bitrateKbps)
        assertEquals(AudioCodec.FLAC, telemetryFormat.codec)
    }

    @Test
    fun `given standard engine path report when created then direct playback remains disabled`() {
        val format = Format.Builder()
            .setSampleMimeType("audio/mpeg")
            .setSampleRate(44_100)
            .build()

        val report = createStandardPathReport(
            format = format,
        )

        assertFalse(report.usedDirectFlag)
        assertFalse(report.usedFloatFallback)
        assertEquals(AudioFormat.ENCODING_INVALID, report.encoding)
        assertEquals(44_100, report.sampleRateHz)
        assertEquals(0, report.audioSessionId)
    }
}

