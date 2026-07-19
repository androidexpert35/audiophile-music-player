package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings

/**
 * User intents emitted from the Settings screen.
 */
sealed interface SettingsUiEvent {
    /** Toggle the audiophile (bit-perfect) playback engine. */
    data class SetAudiophileEngineEnabled(val enabled: Boolean) : SettingsUiEvent

    /** Re-scan connected USB DACs after a missed or stale discovery attempt. */
    data object RefreshUsbAudioDevices : SettingsUiEvent

    /** Ask Android to show the USB DAC permission prompt. */
    data object RequestUsbAudioPermission : SettingsUiEvent


    /** Enable or disable the Sonic Upscaling Enhancer for lossy tracks. */
    data class SetSueEnabled(val enabled: Boolean) : SettingsUiEvent

    /** Enable or disable the Hi-Res Dynamic Remaster for lossless tracks. */
    data class SetHiResRemasterEnabled(val enabled: Boolean) : SettingsUiEvent
}
