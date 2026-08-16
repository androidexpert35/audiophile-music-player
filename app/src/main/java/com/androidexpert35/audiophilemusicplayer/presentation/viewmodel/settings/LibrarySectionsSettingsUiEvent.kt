package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings

import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library.LibraryContentType

/**
 * User intents emitted from the Library Sections settings sub-screen.
 */
sealed interface LibrarySectionsSettingsUiEvent {
    /**
     * Shows or hides a library section. No-ops when [section] is the only section
     * still visible, so the Library screen is never left with zero sections to show.
     */
    data class ToggleVisibility(val section: LibraryContentType) : LibrarySectionsSettingsUiEvent

    /**
     * Moves the row at [fromIndex] to [toIndex] in the displayed order.
     */
    data class MoveSection(val fromIndex: Int, val toIndex: Int) : LibrarySectionsSettingsUiEvent
}
