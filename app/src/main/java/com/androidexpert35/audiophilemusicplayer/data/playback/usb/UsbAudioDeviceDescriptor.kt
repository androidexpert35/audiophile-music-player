package com.androidexpert35.audiophilemusicplayer.data.playback.usb

/**
 * Minimal identity snapshot for a USB audio DAC visible to the app.
 *
 * @property deviceId Stable runtime device identifier assigned by Android.
 * @property deviceName Human-readable label derived from product or device
 *   metadata for display in settings and logs.
 * @property vendorId USB vendor ID reported by the device.
 * @property productId USB product ID reported by the device.
 */
data class UsbAudioDeviceDescriptor(
    val deviceId: Int,
    val deviceName: String,
    val vendorId: Int,
    val productId: Int,
)

