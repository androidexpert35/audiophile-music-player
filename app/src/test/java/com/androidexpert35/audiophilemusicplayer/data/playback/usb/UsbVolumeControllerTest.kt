package com.androidexpert35.audiophilemusicplayer.data.playback.usb

import android.content.SharedPreferences
import android.util.Log
import com.androidexpert35.audiophilemusicplayer.data.repository.SettingsPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UsbVolumeControllerTest {

    private val storedValues = mutableMapOf<String, Int>()
    private val sharedPreferences = mockk<SharedPreferences>()
    private val editor = mockk<SharedPreferences.Editor>()

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { sharedPreferences.getInt(any(), any()) } answers {
            storedValues[firstArg()] ?: secondArg()
        }
        every { sharedPreferences.edit() } returns editor
        every { editor.putInt(any(), any()) } answers {
            storedValues[firstArg()] = secondArg()
            editor
        }
        every { editor.apply() } returns Unit
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `given unseen dacs when activated then each starts at safe fallback and restores independently`() {
        val controller = UsbVolumeController(sharedPreferences)
        val fiio = descriptor(vendorId = 0x2972, productId = 0x0047, name = "FiiO KA1")
        val hiby = descriptor(vendorId = 0x32BB, productId = 0x0004, name = "HiBy FC3")

        controller.activateDevice(fiio)
        assertEquals(60, controller.volumePct.value)
        controller.setVolumePct(24)

        controller.activateDevice(hiby)
        assertEquals(60, controller.volumePct.value)
        controller.setVolumePct(73)

        controller.activateDevice(fiio)
        assertEquals(24, controller.volumePct.value)
        controller.activateDevice(hiby)
        assertEquals(73, controller.volumePct.value)
        assertEquals(2, storedValues.size)
        assertTrue(storedValues.keys.all { it.startsWith(SettingsPreferences.KEY_USB_VOLUME_PCT_PREFIX) })
    }

    @Test
    fun `given same model dacs with serials when activated then their levels remain distinct`() {
        val controller = UsbVolumeController(sharedPreferences)
        val first = descriptor(vendorId = 10, productId = 20, name = "DAC", serial = "first")
        val second = descriptor(vendorId = 10, productId = 20, name = "DAC", serial = "second")

        controller.activateDevice(first)
        controller.setVolumePct(15)
        controller.activateDevice(second)
        controller.setVolumePct(85)

        controller.activateDevice(first)
        assertEquals(15, controller.volumePct.value)
        controller.activateDevice(second)
        assertEquals(85, controller.volumePct.value)
    }

    @Test
    fun `given a pre-1_1 global level when a dac is first activated then that level is inherited`() {
        storedValues[SettingsPreferences.LEGACY_KEY_USB_VOLUME_PCT] = 100
        val controller = UsbVolumeController(sharedPreferences)

        controller.activateDevice(descriptor(vendorId = 0x2972, productId = 0x0047, name = "FiiO KA1"))

        // 100% is the only position the native taper maps to exact unity, so an
        // upgrading listener must not be silently dropped onto the 60% default.
        assertEquals(100, controller.volumePct.value)
    }

    @Test
    fun `given a pre-1_1 global level when a dac level is changed then only the per-device key is written`() {
        storedValues[SettingsPreferences.LEGACY_KEY_USB_VOLUME_PCT] = 100
        val controller = UsbVolumeController(sharedPreferences)
        val fiio = descriptor(vendorId = 0x2972, productId = 0x0047, name = "FiiO KA1")
        val hiby = descriptor(vendorId = 0x32BB, productId = 0x0004, name = "HiBy FC3")

        controller.activateDevice(fiio)
        controller.setVolumePct(42)

        // The legacy value is read-only: it still seeds DACs never adjusted yet …
        assertEquals(100, storedValues[SettingsPreferences.LEGACY_KEY_USB_VOLUME_PCT])
        controller.activateDevice(hiby)
        assertEquals(100, controller.volumePct.value)

        // … while the device that was adjusted owns its own key from then on.
        controller.activateDevice(fiio)
        assertEquals(42, controller.volumePct.value)
    }

    private fun descriptor(
        vendorId: Int,
        productId: Int,
        name: String,
        serial: String? = null,
    ) = UsbAudioDeviceDescriptor(
        deviceId = 1,
        deviceName = name,
        vendorId = vendorId,
        productId = productId,
        serialNumber = serial,
    )
}
