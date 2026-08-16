package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings

/**
 * User intents emitted from the USB &amp; DAC settings sub-screen.
 */
sealed interface UsbSettingsUiEvent {
    /** Re-scan connected USB DACs after a missed or stale discovery attempt. */
    data object RefreshUsbAudioDevices : UsbSettingsUiEvent

    /** Ask Android to show the USB DAC permission prompt. */
    data object RequestUsbAudioPermission : UsbSettingsUiEvent
}
