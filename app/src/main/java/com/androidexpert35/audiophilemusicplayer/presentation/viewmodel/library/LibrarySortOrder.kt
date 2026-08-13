package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library

/**
 * Enumerates the sort strategies available for the library catalogue.
 *
 * Each section's active strategy is stored in [LibraryUiModel.sortOrders] and applied
 * by the ViewModel before the sorted snapshot reaches the UI.
 */
enum class LibrarySortOrder {

    /**
     * Orders by most recent playback activity.
     *
     * Falls back to [RECENTLY_ADDED] order when no playback history is present
     * for a given item, since the domain model does not currently track last-played timestamps.
     */
    RECENTLY_PLAYED,

    /** Orders by the date each item was added to the device's MediaStore index. */
    RECENTLY_ADDED,

    /** Orders alphabetically by item title or name (case-insensitive). */
    ALPHABETICAL
}

