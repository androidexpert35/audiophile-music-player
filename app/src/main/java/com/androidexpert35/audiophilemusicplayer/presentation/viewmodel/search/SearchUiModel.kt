package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.search

import androidx.compose.runtime.Immutable
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Album
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Artist
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track

/**
 * Immutable UI state for the search screen.
 *
 * Holds the current query and the catalogue subsets that match it.
 * All three result lists are empty when no query is active.
 *
 * @property query Current search string entered by the user.
 * @property artistResults Artists whose names contain the active query.
 * @property albumResults Albums whose title or artist name contain the active query.
 * @property trackResults Tracks whose title, artist name, or album title contain the active query.
 * @property isSearchActive Whether a non-blank query is driving the current filter pass.
 * @property isLibraryReady Whether the full catalogue has been loaded into memory
 *   and instant filtering is available; `false` during the initial load pass.
 */
@Immutable
data class SearchUiModel(
    val query: String = "",
    val artistResults: List<Artist> = emptyList(),
    val albumResults: List<Album> = emptyList(),
    val trackResults: List<Track> = emptyList(),
    val isSearchActive: Boolean = false,
    val isLibraryReady: Boolean = false
)

