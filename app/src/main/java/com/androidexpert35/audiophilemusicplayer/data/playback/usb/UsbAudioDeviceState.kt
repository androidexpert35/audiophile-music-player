package com.androidexpert35.audiophilemusicplayer.data.playback.usb

import com.androidexpert35.audiophilemusicplayer.domain.model.audio.DsdOutputMode
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.DsdRate

/**
 * Internal app-wide USB audio readiness snapshot.
 *
 * @property connectedDevice Selected USB DAC candidate, or `null` when none is
 *   attached.
 * @property isPermissionGranted Whether the app currently holds permission for
 *   [connectedDevice].
 * @property supportedProfiles Output formats parsed from USB descriptors or
 *   synthesized from the fallback negotiation sequence.
 * @property areSupportedProfilesEstimated `true` when [supportedProfiles] come
 *   from the fallback negotiation ladder rather than the DAC's exact USB
 *   descriptor table.
 * @property isDirectUsbTransportSupported `true` when Android can initialize at
 *   least one queued USB streaming endpoint for the selected DAC.
 * @property supportedDsdRates Native one-bit DSD families inferred from the
 *   DAC's USB descriptors.
 * @property dsdOutputMode Preferred DSD transport currently available on the
 *   selected output route.
 */
data class UsbAudioDeviceState(
    val connectedDevice: UsbAudioDeviceDescriptor? = null,
    val isPermissionGranted: Boolean = false,
    val supportedProfiles: List<UsbAudioOutputProfile> = emptyList(),
    val areSupportedProfilesEstimated: Boolean = false,
    val isDirectUsbTransportSupported: Boolean = false,
    val supportedDsdRates: List<DsdRate> = emptyList(),
    val dsdOutputMode: DsdOutputMode = DsdOutputMode.Unsupported,
) {
    /** `true` when the audiophile engine can claim a USB DAC immediately. */
    val isDirectUsbReady: Boolean
        get() = connectedDevice != null && isPermissionGranted && isDirectUsbTransportSupported

    /**
     * `true` when the libusb isochronous engine can open the USB DAC.
     *
     * The libusb path wraps the raw file descriptor returned by
     * [android.hardware.usb.UsbManager.openDevice] and submits ISO transfers
     * directly via `USBDEVFS_SUBMITURB`. It does **not** need
     * [isDirectUsbTransportSupported] (which gates the legacy
     * [android.hardware.usb.UsbRequest] path) and therefore works on
     * OEM-kernel-locked devices where the UAC2 class driver
     * has exclusive interface ownership.
     */
    val isLibusbReady: Boolean
        get() = connectedDevice != null && isPermissionGranted
}

