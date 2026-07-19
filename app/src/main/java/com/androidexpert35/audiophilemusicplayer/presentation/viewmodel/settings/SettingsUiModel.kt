package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings

import com.androidexpert35.audiophilemusicplayer.domain.model.audio.SueStatus
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.UsbAudioStatus

/**
 * Immutable UI state for the Settings screen.
 *
 * @property audiophileEngineEnabled Current value of the dual-engine toggle.
 *   When `true` playback prefers the direct USB audiophile engine whenever a
 *   permitted DAC is available; when `false` it falls back to ExoPlayer for
 *   reduced CPU / battery impact.
 * @property isAudiophileEngineSwitchInProgress Whether the app is currently
 *   hot-swapping playback engines after a user toggle.
 * @property usbAudioStatus Current USB DAC availability snapshot.
 * @property isUsbPassthroughPlaybackActive Whether the active audiophile
 *   pipeline is currently sending an unprocessed stream to a USB output.
 * @property activeUsbPlaybackDeviceName Runtime USB output name reported by
 *   playback telemetry, or `null` when USB passthrough is inactive or the
 *   platform has not resolved a product name.
 * @property isUsbDeviceRefreshInProgress Whether USB device discovery is being
 *   retried from the Settings screen.
 * @property sueEnabled Whether the Sonic Upscaling Enhancer is currently enabled
 *   in settings.
 * @property sueStatus Real-time status snapshot from the active audiophile
 *   pipeline, or `null` when no track is loaded or the standard engine is active.
 * @property hiResRemasterEnabled Whether the Hi-Res Dynamic Remaster is currently
 *   enabled in settings.
 */
data class SettingsUiModel(
    val audiophileEngineEnabled: Boolean = false,
    val isAudiophileEngineSwitchInProgress: Boolean = false,
    val usbAudioStatus: UsbAudioStatus = UsbAudioStatus(),
    val isUsbPassthroughPlaybackActive: Boolean = false,
    val activeUsbPlaybackDeviceName: String? = null,
    val isUsbDeviceRefreshInProgress: Boolean = false,
    val sueEnabled: Boolean = true,
    val sueStatus: SueStatus? = null,
    val hiResRemasterEnabled: Boolean = true,
)
