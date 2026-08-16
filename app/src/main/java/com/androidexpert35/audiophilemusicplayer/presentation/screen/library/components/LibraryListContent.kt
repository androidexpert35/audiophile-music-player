package com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library.LibraryContentType
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library.LibraryUiEvent
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library.LibraryUiModel
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library.facetsFor

/**
 * Scrollable single-column list view for all content types.
 *
 * The sort/toggle row appears first in the [LazyColumn]. Every collection in the
 * Playlists section, including the M3U-backed favorites collection, uses the same row.
 *
 * `likedSongIds` is already a [Set], so each item's `isLiked` lookup remains O(1)
 * during scroll without allocating another collection.
 *
 * @param model Current library UI snapshot.
 * @param shellBottomPadding Bottom padding reserving space for the floating shell panel.
 * @param onEvent Callback for user intents.
 */
@Composable
fun LibraryListContent(
    model: LibraryUiModel,
    playingTrackIdProvider: () -> Long?,
    shellBottomPadding: Dp,
    onEvent: (LibraryUiEvent) -> Unit
) {
    // likedSongIds is already a Set<Long> — no copy needed; use the reference directly.
    val likedIds = model.likedSongIds

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 12.dp,
            end = 12.dp,
            top = 0.dp,
            bottom = shellBottomPadding + 8.dp
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Sort + view-toggle row.
        item(key = "sort_toggle_row") {
            LibrarySortToggleRow(
                sortOrder = model.sortOrder,
                isGridView = model.isGridView,
                onSetSortOrder = { onEvent(LibraryUiEvent.SetSortOrder(it)) },
                onToggleViewMode = { onEvent(LibraryUiEvent.ToggleViewMode) }
            )
        }

        when (model.selectedContentType) {
            LibraryContentType.PLAYLISTS -> {
                if (model.playlists.isEmpty()) {
                    item(key = "empty_liked") {
                        LibraryEmptyState(
                            icon = Icons.Filled.LibraryMusic,
                            title = stringResource(R.string.library_empty_playlists_title),
                            message = stringResource(R.string.library_empty_playlists_message),
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
                items(items = model.playlists, key = { playlist -> "playlist_${playlist.id}" }) { playlist ->
                    LibraryPlaylistRow(
                        playlist = playlist,
                        onClick = { onEvent(LibraryUiEvent.OpenPlaylistOverview(playlist)) }
                    )
                }
            }

            LibraryContentType.ALBUMS -> {
                if (model.albums.isEmpty()) {
                    item(key = "empty_albums") {
                        LibraryEmptyState(
                            icon = Icons.Filled.Album,
                            title = stringResource(R.string.library_empty_albums_title),
                            message = stringResource(R.string.library_empty_albums_message),
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                } else {
                    items(
                        items = model.albums,
                        key = { album -> "album_${album.id}" }
                    ) { album ->
                        LibraryAlbumRow(
                            album = album,
                            onClick = { onEvent(LibraryUiEvent.OpenAlbumOverview(album)) }
                        )
                    }
                }
            }

            LibraryContentType.ARTISTS -> {
                if (model.artists.isEmpty()) {
                    item(key = "empty_artists") {
                        LibraryEmptyState(
                            icon = Icons.Filled.Person,
                            title = stringResource(R.string.library_empty_artists_title),
                            message = stringResource(R.string.library_empty_artists_message),
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                } else {
                    items(
                        items = model.artists,
                        key = { artist -> "artist_${artist.id}" }
                    ) { artist ->
                        LibraryArtistRow(
                            artist = artist,
                            onImageRequest = {
                                onEvent(LibraryUiEvent.LoadArtistImage(artist))
                            },
                            onClick = { onEvent(LibraryUiEvent.OpenArtistDescription(artist)) }
                        )
                    }
                }
            }

            LibraryContentType.GENRES,
            LibraryContentType.YEARS,
            LibraryContentType.COMPOSERS -> {
                val facets = model.facetsFor(model.selectedContentType)
                if (facets.isEmpty()) {
                    item(key = "empty_metadata") {
                        LibraryEmptyState(
                            icon = Icons.Filled.LibraryMusic,
                            title = stringResource(R.string.library_empty_metadata_title),
                            message = stringResource(R.string.library_empty_metadata_message),
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                } else {
                    items(items = facets, key = { facet -> "facet_${model.selectedContentType}_${facet.key}" }) { facet ->
                        LibraryFacetRow(
                            facet = facet,
                            onClick = { onEvent(LibraryUiEvent.PlayFacet(facet)) },
                        )
                    }
                }
            }

            // TRACKS displays the full local song catalogue.
            LibraryContentType.TRACKS -> {
                if (model.tracks.isEmpty()) {
                    item(key = "empty_tracks") {
                        LibraryEmptyState(
                            icon = Icons.Filled.LibraryMusic,
                            title = stringResource(R.string.library_empty_tracks_title),
                            message = stringResource(R.string.library_empty_tracks_message),
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                } else {
                    items(
                        items = model.tracks,
                        key = { track -> "track_${track.id}" }
                    ) { track ->
                        val isPlaying by remember(track.id, playingTrackIdProvider) {
                            derivedStateOf { playingTrackIdProvider() == track.id }
                        }
                        LibraryTrackRow(
                            track = track,
                            isCurrentlyPlaying = isPlaying,
                            isLiked = track.id in likedIds,
                            onClick = { onEvent(LibraryUiEvent.PlayTrack(track)) },
                            onLikeClick = { onEvent(LibraryUiEvent.ToggleLikeSong(track)) },
                            onAddToPlaylistClick = { onEvent(LibraryUiEvent.ShowPlaylistPicker(track)) },
                            onPlayNextClick = { onEvent(LibraryUiEvent.PlayNext(track)) },
                            onAddToQueueClick = { onEvent(LibraryUiEvent.AddToQueue(track)) },
                            onGoToAlbumClick = { onEvent(LibraryUiEvent.OpenTrackAlbum(track)) },
                            onGoToArtistClick = { onEvent(LibraryUiEvent.OpenTrackArtist(track)) }
                        )
                    }
                }
            }
        }
    }
}
