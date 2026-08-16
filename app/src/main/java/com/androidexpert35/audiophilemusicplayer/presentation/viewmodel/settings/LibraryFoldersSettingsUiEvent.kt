package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings

/**
 * User intents emitted from the Library Folders settings sub-screen.
 */
sealed interface LibraryFoldersSettingsUiEvent {
    /** Ask the UI to open the system folder chooser so another music folder can be added. */
    data object AddMusicFolderTapped : LibraryFoldersSettingsUiEvent

    /**
     * Delivers the result of the system folder chooser.
     *
     * @property folderId Identifier of the chosen folder, or `null` when the chooser was
     *   dismissed without a selection.
     */
    data class MusicFolderPicked(val folderId: String?) : LibraryFoldersSettingsUiEvent

    /**
     * Drop a folder from the library scan scope.
     *
     * @property folderId Identifier of the folder to stop scanning.
     */
    data class RemoveMusicFolder(val folderId: String) : LibraryFoldersSettingsUiEvent
}
