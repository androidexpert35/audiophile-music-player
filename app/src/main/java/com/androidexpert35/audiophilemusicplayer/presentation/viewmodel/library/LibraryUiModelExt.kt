package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library

/**
 * Sorting and derived-list extensions for [LibraryUiModel].
 *
 * Applies every catalogue section's retained sort strategy before its immutable snapshot
 * reaches Compose. Recently played can only reorder tracks because playback history is
 * recorded at the track level.
 */
internal fun LibraryUiModel.withSortApplied(): LibraryUiModel {
    val recentlyPlayedIndex = recentlyPlayedTrackIds
        .withIndex()
        .associate { (index, id) -> id to index }

    val sortedTracks = when (sortOrders[LibraryContentType.TRACKS]) {
        LibrarySortOrder.RECENTLY_ADDED -> tracks.sortedByDescending { it.dateAdded }
        LibrarySortOrder.ALPHABETICAL -> tracks.sortedBy { it.title.lowercase() }
        LibrarySortOrder.RECENTLY_PLAYED -> tracks.sortedWith { first, second ->
            val firstPosition = recentlyPlayedIndex[first.id] ?: Int.MAX_VALUE
            val secondPosition = recentlyPlayedIndex[second.id] ?: Int.MAX_VALUE
            firstPosition.compareTo(secondPosition)
        }
        null -> tracks
    }
    val sortedAlbums = when (sortOrders[LibraryContentType.ALBUMS]) {
        LibrarySortOrder.RECENTLY_ADDED -> albums.sortedByDescending { it.year }
        LibrarySortOrder.ALPHABETICAL -> albums.sortedBy { it.title.lowercase() }
        else -> albums
    }
    val sortedArtists = when (sortOrders[LibraryContentType.ARTISTS]) {
        LibrarySortOrder.ALPHABETICAL -> artists.sortedBy { it.name.lowercase() }
        else -> artists
    }
    val sortedPlaylists = when (sortOrders[LibraryContentType.PLAYLISTS]) {
        LibrarySortOrder.ALPHABETICAL -> playlists.sortedBy { it.name.lowercase() }
        else -> playlists
    }

    return copy(
        tracks = sortedTracks,
        albums = sortedAlbums,
        artists = sortedArtists,
        playlists = sortedPlaylists
    )
}
