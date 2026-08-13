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

    /** Ask the UI to open the system folder chooser so another music folder can be added. */
    data object AddMusicFolderTapped : SettingsUiEvent

    /**
     * Delivers the result of the system folder chooser.
     *
     * @property folderId Identifier of the chosen folder, or `null` when the chooser was
     *   dismissed without a selection.
     */
    data class MusicFolderPicked(val folderId: String?) : SettingsUiEvent

    /**
     * Drop a folder from the library scan scope.
     *
     * @property folderId Identifier of the folder to stop scanning.
     */
    data class RemoveMusicFolder(val folderId: String) : SettingsUiEvent
}
