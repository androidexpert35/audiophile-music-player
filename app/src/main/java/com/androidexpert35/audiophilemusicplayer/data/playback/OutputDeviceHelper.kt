package com.androidexpert35.audiophilemusicplayer.data.playback

import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * Utility helpers for identifying the most relevant active output route for the
 * standard PCM static rate resolver.
 */
object OutputDeviceHelper {

    /**
     * Returns the highest-priority currently connected media output device.
     *
     * Priority order intentionally mirrors the resolver's routing assumptions:
     * Bluetooth A2DP first, then wired outputs, then the built-in speaker.
     * USB outputs are excluded because USB playback paths are classified and
     * bypassed before the resolver is allowed to run.
     *
     * @param audioManager System audio service used to inspect connected outputs.
     * @return The highest-priority non-USB output device, or `null` when no known
     *   candidate is currently visible.
     */
    fun currentPrimaryOutputDevice(audioManager: AudioManager): AudioDeviceInfo? {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
            ?: outputs.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
            }
            ?: outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
    }

    /**
     * Returns the current primary output device type for static rate resolution.
     *
     * The priority order mirrors Android's normal media-routing preference:
     * Bluetooth A2DP first, then wired outputs, then built-in speaker/earpiece.
     * USB devices are intentionally not selected here because [PathClassifier]
     * bypasses both libusb and Android USB HAL paths before the static resolver
     * can run.
     *
     * @param audioManager System audio service used to inspect connected outputs.
     * @return `AudioDeviceInfo.TYPE_*` for the highest-priority non-USB output,
     *   or [AudioDeviceInfo.TYPE_UNKNOWN] during transient route changes.
     */
    fun currentOutputDeviceType(audioManager: AudioManager): Int {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return outputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }?.type
            ?: outputs.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
            }?.type
            ?: outputs.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ||
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            }?.type
            ?: AudioDeviceInfo.TYPE_UNKNOWN
    }

    /**
     * Returns `true` when Android currently reports a USB audio output routed
     * through the platform USB Audio HAL.
     *
     * @param audioManager System audio service used to inspect connected outputs.
     * @return `true` when a USB output device is present.
     */
    fun hasAndroidUsbAudioOutput(audioManager: AudioManager): Boolean =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { device ->
            device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
        }

    /**
     * Returns `true` when [deviceType] represents a Bluetooth media output.
     *
     * @param deviceType `AudioDeviceInfo.TYPE_*` constant to inspect.
     * @return `true` for Bluetooth A2DP outputs.
     */
    fun isBluetoothOutput(deviceType: Int): Boolean =
        deviceType == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP

    /**
     * Converts an `AudioDeviceInfo.TYPE_*` constant into a compact diagnostic tag.
     *
     * @param deviceType `AudioDeviceInfo.TYPE_*` constant describing an output route.
     * @return Short route label suitable for logs and telemetry reasons.
     */
    fun describeDeviceType(deviceType: Int): String = when (deviceType) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "SPEAKER"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT_A2DP"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED"
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB"
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER -> "BLE"
        AudioDeviceInfo.TYPE_UNKNOWN,
        0 -> "UNKNOWN"
        else -> "UNKNOWN_$deviceType"
    }
}

