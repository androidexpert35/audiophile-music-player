package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings

/**
 * One-shot effects emitted from the Library Folders settings ViewModel.
 */
sealed interface LibraryFoldersSettingsUiEffect {
    /**
     * Requests a transient error surface when a folder command fails.
     *
     * @property message Human-readable failure message shown to the user.
     */
    data class ToggleError(val message: String) : LibraryFoldersSettingsUiEffect

    /** Requests that the Compose layer open the system folder chooser. */
    data object PickMusicFolder : LibraryFoldersSettingsUiEffect
}
