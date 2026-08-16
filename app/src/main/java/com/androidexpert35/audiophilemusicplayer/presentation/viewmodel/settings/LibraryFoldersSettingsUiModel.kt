package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings

import com.androidexpert35.audiophilemusicplayer.domain.model.library.MusicFolder

/**
 * Immutable UI state for the Library Folders settings sub-screen.
 *
 * @property musicFolders Storage locations the library is scanned from. Empty means the
 *   catalogue cannot be rebuilt until the user adds a folder.
 */
data class LibraryFoldersSettingsUiModel(
    val musicFolders: List<MusicFolder> = emptyList(),
)
