package com.androidexpert35.audiophilemusicplayer.data.playback

import android.media.AudioDeviceInfo
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.OutputRouteKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** Verifies framework device types are reduced to stable domain route families. */
class OutputRouteKindMapperTest {

    @Test
    fun `given Bluetooth transport types when mapped then route is Bluetooth`() {
        val bluetoothTypes = listOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            AudioDeviceInfo.TYPE_BLE_BROADCAST,
            AudioDeviceInfo.TYPE_HEARING_AID,
        )

        bluetoothTypes.forEach { deviceType ->
            assertEquals(
                OutputRouteKind.BLUETOOTH,
                mapOutputRouteKind(deviceType, isDirectUsbBypass = false),
            )
        }
    }

    @Test
    fun `given libusb sentinel collision when bypass is active then route is USB`() {
        assertEquals(
            OutputRouteKind.USB,
            mapOutputRouteKind(
                deviceType = USB_CLASS_AUDIO_SENTINEL,
                isDirectUsbBypass = true,
            ),
        )
    }

    @Test
    fun `given built in speaker when mapped then route is built in`() {
        assertEquals(
            OutputRouteKind.BUILT_IN,
            mapOutputRouteKind(
                deviceType = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                isDirectUsbBypass = false,
            ),
        )
    }
}
