package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.search

import com.androidexpert35.audiophilemusicplayer.R
import com.tony.coreui.data.strings.CoreUiStringProvider

/**
 * Centralized user-facing copy and formatting helpers for the search feature.
 *
 * Keeping strings here avoids scattering raw literals across Composables and
 * makes copy changes and localization straightforward.
 */
object SearchStrings {

    /** Screen title shown in the top app bar. */
    val title: String
        get() = CoreUiStringProvider.get(R.string.nav_search)

    /** Placeholder text displayed inside the empty search field. */
    val searchPlaceholder: String
        get() = CoreUiStringProvider.get(R.string.search_placeholder)

    /** Accessibility label for the clear-search icon button. */
    val clearSearchContentDescription: String
        get() = CoreUiStringProvider.get(R.string.search_clear_content_description)

    /** Primary title for the idle (no-query) empty state. */
    val idleTitle: String
        get() = CoreUiStringProvider.get(R.string.search_idle_title)

    /** Supporting message for the idle empty state. */
    val idleMessage: String
        get() = CoreUiStringProvider.get(R.string.search_idle_message)

    /** Primary title when a query returns no matches. */
    val noResultsTitle: String
        get() = CoreUiStringProvider.get(R.string.search_no_results_title)

    /** Supporting message when a query returns no matches. */
    val noResultsMessage: String
        get() = CoreUiStringProvider.get(R.string.search_no_results_message)

    /** Section header label for artist results. */
    val artistsSectionLabel: String
        get() = CoreUiStringProvider.get(R.string.library_artists_section_label)

    /** Section header label for album results. */
    val albumsSectionLabel: String
        get() = CoreUiStringProvider.get(R.string.library_albums_section_label)

    /** Section header label for song / track results. */
    val songsSectionLabel: String
        get() = CoreUiStringProvider.get(R.string.search_songs_section_label)

    /** Error shown when a playback command is issued but the track queue is empty. */
    val playbackEmptyQueue: String
        get() = CoreUiStringProvider.get(R.string.search_playback_empty_queue)

    /**
     * Builds the count badge string appended to each section header.
     *
     * @param count Number of results in the section.
     * @return Formatted string such as "(12)".
     */
    fun sectionCount(count: Int): String =
        CoreUiStringProvider.get(R.string.search_section_count_badge, count)
}

