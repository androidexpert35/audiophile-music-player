package com.androidexpert35.audiophilemusicplayer.data.playback.usb

/**
 * USB lifecycle events emitted by [UsbDeviceScanner].
 */
sealed interface UsbAudioEvent {

    /**
     * Signals that a compatible USB audio DAC has been attached.
     *
     * @property device Connected DAC identity snapshot.
     */
    data class DeviceAttached(val device: UsbAudioDeviceDescriptor) : UsbAudioEvent

    /**
     * Signals that a previously visible USB audio DAC has been detached.
     *
     * @property deviceId Runtime identifier of the removed device.
     */
    data class DeviceDetached(val deviceId: Int) : UsbAudioEvent

    /**
     * Signals that the app may now open the device directly through UsbManager.
     *
     * @property device DAC identity snapshot.
     * @property supportedProfiles Parsed or fallback-supported output formats.
     */
    data class PermissionGranted(
        val device: UsbAudioDeviceDescriptor,
        val supportedProfiles: List<UsbAudioOutputProfile>,
    ) : UsbAudioEvent

    /**
     * Signals that the direct USB permission request was rejected.
     *
     * @property device DAC identity snapshot.
     */
    data class PermissionDenied(val device: UsbAudioDeviceDescriptor) : UsbAudioEvent
}

