package com.androidexpert35.audiophilemusicplayer.data.playback

import android.media.AudioDeviceInfo
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.OutputRouteKind

/**
 * Converts Android output device types into stable domain transport families.
 *
 * @param deviceType `AudioDeviceInfo.TYPE_*` reported for the active media route.
 * @param isDirectUsbBypass Whether the report represents the raw libusb path.
 * @return Framework-free route family suitable for domain telemetry.
 */
internal fun mapOutputRouteKind(
    deviceType: Int,
    isDirectUsbBypass: Boolean,
): OutputRouteKind {
    if (isDirectUsbBypass) return OutputRouteKind.USB

    return when (deviceType) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST,
        AudioDeviceInfo.TYPE_HEARING_AID -> OutputRouteKind.BLUETOOTH

        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> OutputRouteKind.USB

        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL,
        AudioDeviceInfo.TYPE_AUX_LINE -> OutputRouteKind.WIRED

        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> OutputRouteKind.BUILT_IN

        AudioDeviceInfo.TYPE_UNKNOWN,
        0 -> OutputRouteKind.UNKNOWN

        else -> OutputRouteKind.OTHER
    }
}
