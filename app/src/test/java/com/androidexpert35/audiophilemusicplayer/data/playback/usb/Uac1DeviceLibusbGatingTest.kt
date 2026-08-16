package com.androidexpert35.audiophilemusicplayer.data.playback.usb

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Field-bug reproduction: UAC1.0 full-speed DACs pass every Kotlin-side gate
 * that routes playback onto the libusb engine, even though that engine is
 * UAC2-only.
 *
 * Bluetooth/USB combo DACs built on Qualcomm QCC51xx (FiiO/SNOWSKY Retro Nano,
 * iFi GO blu, Qudelix 5K, Shanling UP4/5, …) enumerate as USB Audio Class 1.0
 * full-speed devices. Their class-specific FORMAT_TYPE_I descriptor carries the
 * discrete sample-rate table *inline* (UAC1 layout) — the exact layout
 * [UsbAudioDescriptorParser.parseTypeIFormats] reads — so the Kotlin layer
 * happily reports rich `supportedProfiles`, and
 * [UsbAudioDeviceState.isLibusbReady] is `true` on permission alone. Nothing on
 * the Kotlin side ever checks `bInterfaceProtocol == 0x20` (UAC2) or the bus
 * speed, so the audiophile engine commits to the UAC2-only libusb pipeline and
 * only fails deep inside native endpoint selection.
 *
 * A UAC1 device also contains no UAC2 CLOCK_SOURCE (0x24/0x0A) descriptor, so
 * `uac2_find_clock_source_id()` cannot succeed and
 * `uac2_force_clock_sample_rate()` falls back to probing speculative clock IDs
 * (41, 40, 10, 11, 12, 1) — control transfers a UAC1 firmware never expects.
 * The descriptor assertions below pin down both facts against a realistic
 * QCC-style descriptor image.
 */
class Uac1DeviceLibusbGatingTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    /**
     * Class-specific descriptor block modelled on a QCC51xx-class UAC1 BT/USB
     * combo DAC: one AudioControl HEADER (UAC1 bcdADC=1.00) plus one
     * AudioStreaming FORMAT_TYPE_I with three discrete rates (44.1/48/96 kHz),
     * stereo, 24-bit in a 3-byte subframe. Layout per USB Audio 1.0 §4.5.3 /
     * Frmts10 §2.2.5.
     */
    private fun uac1RawDescriptors(): ByteArray = byteArrayOf(
        // ── UAC1 AC HEADER: 09 24 01 bcdADC=0x0100 wTotalLength=0x001E inCollection=1 baInterfaceNr=1
        0x09, 0x24, 0x01, 0x00, 0x01, 0x1E, 0x00, 0x01, 0x01,
        // ── UAC1 AC FEATURE UNIT (subtype 0x06) id=2, source=1, controls
        0x0A, 0x24, 0x06, 0x02, 0x01, 0x01, 0x03, 0x00, 0x00, 0x00,
        // ── UAC1 AS GENERAL (subtype 0x01): 07 24 01 terminalLink=1 delay=1 wFormatTag=PCM(0x0001)
        0x07, 0x24, 0x01, 0x01, 0x01, 0x01, 0x00,
        // ── UAC1 FORMAT_TYPE_I (subtype 0x02): bNrChannels=2 bSubframeSize=3
        //    bBitResolution=24 bSamFreqType=3, rates 44100 / 48000 / 96000 (24-bit LE)
        0x11, 0x24, 0x02, 0x01, 0x02, 0x03, 0x18, 0x03,
        0x44.toByte(), 0xAC.toByte(), 0x00,   // 44100
        0x80.toByte(), 0xBB.toByte(), 0x00,   // 48000
        0x00, 0x77, 0x01,                     // 96000
    )

    @Test
    fun `uac1 descriptor image parses into full pcm profile table on the kotlin side`() {
        val profiles = UsbAudioDescriptorParser().parseTypeIFormats(uac1RawDescriptors())

        // The Kotlin parser reads the UAC1 inline sample-rate table verbatim —
        // a UAC1-only DAC therefore looks fully capable to the routing layer.
        assertEquals(
            listOf(44_100, 48_000, 96_000),
            profiles.map(UsbAudioOutputProfile::sampleRateHz).sorted(),
        )
        assertTrue(profiles.all { it.bitDepth == 24 && it.channelCount == 2 })
    }

    @Test
    fun `libusb readiness gate accepts a uac1 device on permission alone`() {
        val state = UsbAudioDeviceState(
            connectedDevice = UsbAudioDeviceDescriptor(
                deviceId = 1002,
                deviceName = "QCC51xx BT/USB DAC",
                vendorId = 0x0A12,      // Qualcomm/CSR
                productId = 0x4007,
            ),
            isPermissionGranted = true,
            supportedProfiles = UsbAudioDescriptorParser().parseTypeIFormats(uac1RawDescriptors()),
            // Kernel-locked interface: UsbRequest probing failed…
            isDirectUsbTransportSupported = false,
        )

        // …yet the libusb gate still reports ready: no UAC-version or bus-speed
        // check exists anywhere on the Kotlin side. The UAC2-only native
        // pipeline is entered unconditionally for a UAC1 full-speed device.
        assertTrue(state.isLibusbReady)
        assertFalse(state.isDirectUsbReady)
    }

    @Test
    fun `uac1 descriptor image contains no uac2 clock source entity`() {
        val raw = uac1RawDescriptors()

        // Walk descriptors exactly like uac2_find_clock_source_id(): look for
        // CS_INTERFACE (0x24) with bDescriptorSubtype CLOCK_SOURCE (0x0A).
        var offset = 0
        var clockSourceFound = false
        while (offset + 2 < raw.size) {
            val len = raw[offset].toInt() and 0xFF
            if (len < 3 || offset + len > raw.size) break
            val type = raw[offset + 1].toInt() and 0xFF
            val subtype = raw[offset + 2].toInt() and 0xFF
            if (type == 0x24 && subtype == 0x0A) clockSourceFound = true
            offset += len
        }

        // UAC1 has no Clock Source entity (AC subtypes stop at 0x08), so the
        // native auto-detect must fail and uac2_force_clock_sample_rate() will
        // fall through to the speculative clock-ID list — SET_CUR requests this
        // firmware class never expects on its Audio Control interface.
        assertFalse(clockSourceFound)
    }
}
