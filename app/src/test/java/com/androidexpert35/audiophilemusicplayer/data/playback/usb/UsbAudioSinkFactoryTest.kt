package com.androidexpert35.audiophilemusicplayer.data.playback.usb

import android.content.Context
import android.hardware.usb.UsbManager
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsbAudioSinkFactoryTest {

    private val factory = UsbAudioSinkFactory(
        context = mockk<Context>(relaxed = true),
        usbDeviceScanner = mockk<UsbDeviceScanner>(relaxed = true),
        usbManager = mockk<UsbManager>(relaxed = true),
        usbBitPerfectRouter = mockk<UsbBitPerfectRouter>(relaxed = true),
        usbVolumeController = mockk<UsbVolumeController>(relaxed = true),
    )

    @Test
    fun `given source profile is advertised when resolving direct usb profile then source profile is selected`() {
        val sourceProfile = UsbAudioOutputProfile(
            sampleRateHz = 192_000,
            bitDepth = 24,
            channelCount = 2,
        )

        val selectedProfile = factory.resolveDirectUsbProfile(
            sourceProfile = sourceProfile,
            supportedProfiles = listOf(sourceProfile),
        )

        assertEquals(sourceProfile, selectedProfile)
    }

    @Test
    fun `given source profile is not advertised when resolving direct usb profile then platform fallback is requested`() {
        val sourceProfile = UsbAudioOutputProfile(
            sampleRateHz = 192_000,
            bitDepth = 24,
            channelCount = 2,
        )

        val selectedProfile = factory.resolveDirectUsbProfile(
            sourceProfile = sourceProfile,
            supportedProfiles = listOf(
                UsbAudioOutputProfile(
                    sampleRateHz = 192_000,
                    bitDepth = 32,
                    channelCount = 2,
                )
            ),
        )

        assertNull(selectedProfile)
    }

    @Test
    fun `given only mismatched sample rates are advertised when resolving direct usb profile then platform fallback is requested`() {
        val selectedProfile = factory.resolveDirectUsbProfile(
            sourceProfile = UsbAudioOutputProfile(
                sampleRateHz = 192_000,
                bitDepth = 24,
                channelCount = 2,
            ),
            supportedProfiles = listOf(
                UsbAudioOutputProfile(
                    sampleRateHz = 48_000,
                    bitDepth = 24,
                    channelCount = 2,
                )
            ),
        )

        assertNull(selectedProfile)
    }
}
