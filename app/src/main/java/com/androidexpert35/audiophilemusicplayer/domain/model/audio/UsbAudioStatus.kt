package com.androidexpert35.audiophilemusicplayer.domain.model.audio

/**
 * Immutable snapshot describing whether direct USB audiophile playback is
 * currently possible.
 *
 * @property isDeviceConnected `true` when at least one USB audio DAC is
 *   attached to the device.
 * @property isPermissionGranted `true` when the app holds USB host permission
 *   for the selected DAC.
 * @property isDirectOutputReady `true` when a connected DAC is both present and
 *   permitted, allowing the audiophile engine to claim it.
 * @property isDirectUsbTransportSupported `true` when the permitted DAC
 *   advertises a direct-compatible UAC2 streaming interface. The real claim is
 *   attempted only when playback starts.
 * @property activeDeviceName Human-readable name of the currently selected USB
 *   DAC, or `null` when no compatible device is connected.
 * @property supportedFormats USB output formats reported for the selected DAC.
 * @property areSupportedFormatsEstimated `true` when descriptor parsing failed
 *   and the app had to fall back to an estimated negotiation ladder instead of
 *   the DAC's exact advertised format table.
 * @property dsdOutputMode Preferred DSD transport currently available on the
 *   active output route.
 */
data class UsbAudioStatus(
    val isDeviceConnected: Boolean = false,
    val isPermissionGranted: Boolean = false,
    val isDirectOutputReady: Boolean = false,
    val isDirectUsbTransportSupported: Boolean = false,
    val activeDeviceName: String? = null,
    val supportedFormats: List<UsbAudioFormat> = emptyList(),
    val areSupportedFormatsEstimated: Boolean = false,
    val dsdOutputMode: DsdOutputMode = DsdOutputMode.Unsupported,
)
