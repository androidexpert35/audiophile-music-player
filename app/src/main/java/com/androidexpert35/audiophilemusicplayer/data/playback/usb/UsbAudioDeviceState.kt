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
 * @property isDirectUsbTransportSupported `true` when the permitted device
 *   advertises a UAC2 streaming interface eligible for direct negotiation.
 *   The real interface claim is deferred until playback starts.
 * @property isUac2Protocol `true` when [connectedDevice] exposes at least one
 *   UAC 2.0 AudioStreaming interface (`bInterfaceProtocol = 0x20`). UAC1
 *   full-speed devices (Bluetooth/USB combo DACs and dongles in their UAC1
 *   compatibility mode) report `false` and must stay on the platform
 *   AudioTrack path — every direct-USB transport in the app speaks UAC2 only.
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
    val isUac2Protocol: Boolean = false,
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
     *
     * [isUac2Protocol] is required: the libusb pipeline programs the UAC2
     * Clock Source entity and schedules High-Speed microframes, neither of
     * which exists on a UAC1 full-speed device. Routing a UAC1 DAC here used
     * to fail deep inside native endpoint selection after the device had
     * already been disturbed by claim/control traffic (the field "BT combo DAC
     * switches off in audiophile mode" bug).
     */
    val isLibusbReady: Boolean
        get() = connectedDevice != null && isPermissionGranted && isUac2Protocol
}
