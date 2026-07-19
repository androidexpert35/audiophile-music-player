package com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile

import android.media.AudioFormat
import com.androidexpert35.audiophilemusicplayer.data.playback.dsd.DsdCapabilityDetector
import com.androidexpert35.audiophilemusicplayer.data.playback.native_.AudioFormatInfo
import com.androidexpert35.audiophilemusicplayer.data.playback.usb.LibusbPcmEnhancedSink
import com.androidexpert35.audiophilemusicplayer.data.playback.usb.UsbAudioDeviceState
import com.androidexpert35.audiophilemusicplayer.data.playback.usb.UsbAudioSinkFactory
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioCodec
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.Assert.assertSame
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Verifies PCM transport selection remains valid with and without a USB DAC. */
class BitPerfectSinkRouterTest {

    private val usbAudioSinkFactory = mockk<UsbAudioSinkFactory>()
    private val router = BitPerfectSinkRouter(
        usbAudioSinkFactory = usbAudioSinkFactory,
        dsdCapabilityDetector = mockk<DsdCapabilityDetector>(),
    )

    @Before
    fun setUpAndroidLog() {
        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any(), any()) } returns 0
    }

    @After
    fun tearDownAndroidLog() {
        unmockkStatic(android.util.Log::class)
    }

    @Test
    fun `given active enhancement and no usb dac when selecting sink then platform output remains available`() {
        val format = enhancedFormat()
        val sueStage = mockk<SueStage>()
        val platformSink = mockk<AudiophileOutputSink>()

        every { sueStage.isActive } returns true
        every { usbAudioSinkFactory.currentUsbDeviceState() } returns UsbAudioDeviceState()
        every { usbAudioSinkFactory.create(format) } returns platformSink

        val selected = router.createSinkForTrackFormat(
            format = format,
            dsdPlaybackContext = null,
            sourcePath = "/proc/self/fd/42",
            sueStage = sueStage,
        )

        assertSame(platformSink, selected)
        verify(exactly = 1) { usbAudioSinkFactory.create(format) }
        verify(exactly = 0) { usbAudioSinkFactory.createLibusbPcmEnhancedSink(any()) }
        verify(exactly = 0) { usbAudioSinkFactory.createLibusbPcmSink(any(), any()) }
    }

    @Test
    fun `given active enhancement and libusb dac when selecting sink then enhanced usb transport is retained`() {
        val format = enhancedFormat()
        val sueStage = mockk<SueStage>()
        val usbState = mockk<UsbAudioDeviceState>()
        val enhancedSink = mockk<LibusbPcmEnhancedSink>()

        every { sueStage.isActive } returns true
        every { usbState.isLibusbReady } returns true
        every { usbAudioSinkFactory.currentUsbDeviceState() } returns usbState
        every { usbAudioSinkFactory.createLibusbPcmEnhancedSink(format) } returns enhancedSink

        val selected = router.createSinkForTrackFormat(
            format = format,
            dsdPlaybackContext = null,
            sourcePath = "/proc/self/fd/42",
            sueStage = sueStage,
        )

        assertSame(enhancedSink, selected)
        verify(exactly = 1) { usbAudioSinkFactory.createLibusbPcmEnhancedSink(format) }
        verify(exactly = 0) { usbAudioSinkFactory.create(any()) }
        verify(exactly = 0) { usbAudioSinkFactory.createLibusbPcmSink(any(), any()) }
    }

    private fun enhancedFormat(): AudioFormatInfo = AudioFormatInfo(
        sampleRateHz = 96_000,
        channelCount = 2,
        sourceBitDepth = 32,
        androidPcmEncoding = AudioFormat.ENCODING_PCM_FLOAT,
        bytesPerSample = Float.SIZE_BYTES,
        durationMs = 0L,
        bitrateKbps = 256,
        codec = AudioCodec.AAC,
    )
}
