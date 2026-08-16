package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library

import androidx.compose.runtime.Immutable
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Album
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Artist
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track

/**
 * Immutable UI state for the music library browser screen.
 *
 * The mini-player is handled globally by the shared app shell — this model
 * only contains library-specific data. Track search belongs to the dedicated
 * Search destination and is intentionally absent from this model.
 *
 * @property tracks All audio tracks on the device.
 * @property playlists Locally stored M3U playlists rendered in the Playlists section.
 * @property albums All albums on the device.
 * @property artists All artists on the device.
 * @property likedSongIds Set of track IDs the user has liked; drives heart-icon state on each row.
 * @property recentlyPlayedTrackIds Ordered list of track IDs played most-recently first;
 *   used by [LibrarySortOrder.RECENTLY_PLAYED] to sort the visible list.
 * @property selectedContentType Section currently visible in the catalogue surface.
 *   Defaults to the first chip, [LibraryContentType.TRACKS], when the library opens.
 * @property gridViews View mode retained independently for every catalogue section.
 *   Every section defaults to the list view.
 * @property sortOrders Sort strategy retained independently for every catalogue section.
 *   Every section defaults to [LibrarySortOrder.RECENTLY_ADDED].
 * @property isRefreshing `true` while a user-requested or MediaStore-change re-index is in
 *   progress; drives the spinning animation on the header icon.
 * @property isCreatePlaylistDialogVisible Whether the create-playlist dialog is open.
 * @property playlistPickerTrack Track awaiting a target playlist selection, if any.
 * @property visibleOrderedSections Catalogue sections to render as filter chips, already
 *   filtered by the user's saved visibility choice and ordered by their saved display
 *   order. Defaults to every section in its declaration order, matching the app's
 *   original behavior before section visibility/order existed as a setting.
 */
@Immutable
data class LibraryUiModel(
    val tracks: List<Track> = emptyList(),
    val playlists: List<PlaylistUiModel> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val likedSongIds: Set<Long> = emptySet(),
    val recentlyPlayedTrackIds: List<Long> = emptyList(),
    val selectedContentType: LibraryContentType = LibraryContentType.TRACKS,
    val gridViews: Map<LibraryContentType, Boolean> =
        LibraryContentType.entries.associateWith { false },
    val sortOrders: Map<LibraryContentType, LibrarySortOrder> =
        LibraryContentType.entries.associateWith { LibrarySortOrder.RECENTLY_ADDED },
    val isRefreshing: Boolean = false,
    val isCreatePlaylistDialogVisible: Boolean = false,
    val playlistPickerTrack: Track? = null,
    val visibleOrderedSections: List<LibraryContentType> = LibraryContentType.entries.toList(),
) {
    /** Sort strategy currently selected for the visible catalogue section. */
    val sortOrder: LibrarySortOrder
        get() = sortOrders[selectedContentType] ?: LibrarySortOrder.RECENTLY_ADDED

    /** Whether the visible catalogue section uses the two-column grid view. */
    val isGridView: Boolean
        get() = gridViews[selectedContentType] ?: false

    /** Genre groups derived locally from the indexed tracks' metadata. */
    val genreFacets: List<LibraryFacet>
        get() = tracks.toGenreFacets()

    /** Release-year groups derived locally from the indexed tracks' metadata. */
    val yearFacets: List<LibraryFacet>
        get() = tracks.toYearFacets()

    /** Composer groups derived locally from the indexed tracks' metadata. */
    val composerFacets: List<LibraryFacet>
        get() = tracks.toComposerFacets()

    /**
     * Derived ordered list of recently-played [Track] objects, most-recent first.
     *
     * Tracks that appear in [recentlyPlayedTrackIds] but are no longer in [tracks]
     * (e.g. deleted from device) are silently skipped.
     */
    val recentlyPlayedTracks: List<Track>
        get() {
            val trackMap = tracks.associateBy { it.id }
            return recentlyPlayedTrackIds.mapNotNull { id -> trackMap[id] }
        }
}

/** Returns the metadata facets for [contentType], sorted with that section's saved choice. */
internal fun LibraryUiModel.facetsFor(contentType: LibraryContentType): List<LibraryFacet> {
    val facets = when (contentType) {
        LibraryContentType.GENRES -> genreFacets
        LibraryContentType.YEARS -> yearFacets
        LibraryContentType.COMPOSERS -> composerFacets
        else -> emptyList()
    }
    val recentlyPlayedIndex = recentlyPlayedTrackIds.withIndex().associate { (index, id) -> id to index }

    return when (sortOrders[contentType] ?: LibrarySortOrder.RECENTLY_ADDED) {
        LibrarySortOrder.ALPHABETICAL -> facets.sortedBy { it.name.lowercase() }
        LibrarySortOrder.RECENTLY_ADDED -> facets.sortedByDescending { facet ->
            facet.tracks.maxOfOrNull(Track::dateAdded) ?: Long.MIN_VALUE
        }
        LibrarySortOrder.RECENTLY_PLAYED -> facets.sortedWith(
            compareBy<LibraryFacet> { facet ->
                facet.tracks.minOfOrNull { track -> recentlyPlayedIndex[track.id] ?: Int.MAX_VALUE }
                    ?: Int.MAX_VALUE
            }.thenBy { it.name.lowercase() }
        )
    }
}
