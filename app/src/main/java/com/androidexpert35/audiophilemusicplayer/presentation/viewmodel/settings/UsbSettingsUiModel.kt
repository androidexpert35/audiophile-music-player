package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings

import com.androidexpert35.audiophilemusicplayer.domain.model.audio.UsbAudioStatus

/**
 * Immutable UI state for the USB &amp; DAC settings sub-screen.
 *
 * @property usbAudioStatus Current USB DAC availability snapshot.
 * @property isUsbPlaybackActive Whether runtime telemetry confirms that the active
 *   audiophile pipeline is currently sending audio to a USB output, including
 *   intentionally processed enhancement paths.
 * @property activeUsbPlaybackDeviceName Runtime USB output name reported by playback
 *   telemetry, or `null` when USB playback is inactive or the platform has not
 *   resolved a product name.
 * @property isUsbDeviceRefreshInProgress Whether USB device discovery is being retried.
 */
data class UsbSettingsUiModel(
    val usbAudioStatus: UsbAudioStatus = UsbAudioStatus(),
    val isUsbPlaybackActive: Boolean = false,
    val activeUsbPlaybackDeviceName: String? = null,
    val isUsbDeviceRefreshInProgress: Boolean = false,
)
