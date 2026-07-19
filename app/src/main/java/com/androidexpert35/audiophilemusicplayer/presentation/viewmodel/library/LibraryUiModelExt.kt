package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library

/**
 * Sorting and derived-list extensions for [LibraryUiModel].
 *
 * Extracted from [LibraryViewModel] so that the transformation logic lives
 * in a dedicated `*Ext.kt` file close to the type it extends, per the project's
 * component-separation guidelines.
 *
 * @see LibraryViewModel
 */

/**
 * Returns a copy of this model with all list items sorted according to [LibraryUiModel.sortOrder].
 *
 * - [LibrarySortOrder.RECENTLY_ADDED]: tracks sorted descending by [Track.dateAdded];
 *   albums sorted descending by [Album.year] as a proxy for recency.
 * - [LibrarySortOrder.ALPHABETICAL]: all lists sorted case-insensitively by name / title.
 * - [LibrarySortOrder.RECENTLY_PLAYED]: tracks re-ordered so played tracks come first
 *   (in order of most-recent play), followed by any unplayed tracks. Albums and
 *   artists are unaffected — play history is tracked at the track level only.
 *
 * @return A new [LibraryUiModel] with all applicable lists re-sorted.
 */
internal fun LibraryUiModel.withSortApplied(): LibraryUiModel = when (sortOrder) {
    LibrarySortOrder.RECENTLY_ADDED -> copy(
        tracks = tracks.sortedByDescending { it.dateAdded },
        albums = albums.sortedByDescending { it.year }
    )
    LibrarySortOrder.ALPHABETICAL -> copy(
        tracks = tracks.sortedBy { it.title.lowercase() },
        albums = albums.sortedBy { it.title.lowercase() },
        artists = artists.sortedBy { it.name.lowercase() }
    )
    LibrarySortOrder.RECENTLY_PLAYED -> {
        // Build an index from trackId → position in recently-played list so the
        // sort is O(n log n) rather than O(n²) for large libraries.
        val recentlyPlayedIndex = recentlyPlayedTrackIds
            .withIndex()
            .associate { (index, id) -> id to index }
        copy(
            tracks = tracks.sortedWith { a, b ->
                val aPos = recentlyPlayedIndex[a.id] ?: Int.MAX_VALUE
                val bPos = recentlyPlayedIndex[b.id] ?: Int.MAX_VALUE
                aPos.compareTo(bPos)
            }
        )
    }
}

