package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings

import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library.LibraryContentType

/**
 * One row of the Library Sections settings sub-screen.
 *
 * @property section Library catalogue section this row represents.
 * @property isVisible Whether the section currently appears in the Library's filter row.
 */
data class LibrarySectionRow(
    val section: LibraryContentType,
    val isVisible: Boolean,
)

/**
 * Immutable UI state for the Library Sections settings sub-screen.
 *
 * @property rows Every library section, in the user's saved display order, each with
 *   its current visibility.
 */
data class LibrarySectionsSettingsUiModel(
    val rows: List<LibrarySectionRow> = emptyList(),
)
