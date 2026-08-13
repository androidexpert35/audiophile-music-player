package com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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

/**
 * Scrollable grid view for every library content type.
 *
 * The sort/toggle row appears as a full-width span at the top of the
 * [LazyVerticalGrid] so it participates in the same scroll physics as the grid
 * cells below.
 *
 * @param model Current library UI snapshot.
 * @param shellBottomPadding Bottom padding reserving space for the floating shell panel.
 * @param onEvent Callback for user intents.
 */
@Composable
fun LibraryGridContent(
    model: LibraryUiModel,
    playingTrackIdProvider: () -> Long?,
    shellBottomPadding: Dp,
    onEvent: (LibraryUiEvent) -> Unit
) {
    val columnCount = when (model.selectedContentType) {
        LibraryContentType.ALBUMS,
        LibraryContentType.ARTISTS -> 3
        LibraryContentType.TRACKS,
        LibraryContentType.PLAYLISTS -> 2
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(columnCount),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 12.dp,
            end = 12.dp,
            top = 0.dp,
            bottom = shellBottomPadding + 8.dp
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Sort row spans both columns so it always appears full-width.
        item(key = "sort_toggle_row", span = { GridItemSpan(maxLineSpan) }) {
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
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        LibraryEmptyState(
                            icon = Icons.Filled.LibraryMusic,
                            title = stringResource(R.string.library_empty_playlists_title),
                            message = stringResource(R.string.library_empty_playlists_message),
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                } else {
                    items(items = model.playlists, key = { playlist -> "playlist_grid_${playlist.id}" }) { playlist ->
                        LibraryPlaylistRow(
                            playlist = playlist,
                            onClick = { onEvent(LibraryUiEvent.OpenPlaylistOverview(playlist)) }
                        )
                    }
                }
            }

            LibraryContentType.ALBUMS -> {
                if (model.albums.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
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
                        LibraryAlbumGridItem(
                            album = album,
                            onClick = { onEvent(LibraryUiEvent.OpenAlbumOverview(album)) }
                        )
                    }
                }
            }

            LibraryContentType.ARTISTS -> {
                if (model.artists.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        LibraryEmptyState(
                            icon = Icons.Filled.Person,
                            title = stringResource(R.string.library_empty_artists_title),
                            message = stringResource(R.string.library_empty_artists_message),
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                } else {
                    items(items = model.artists, key = { artist -> "artist_grid_${artist.id}" }) { artist ->
                        LibraryArtistGridItem(
                            artist = artist,
                            onImageRequest = { onEvent(LibraryUiEvent.LoadArtistImage(artist)) },
                            onClick = { onEvent(LibraryUiEvent.OpenArtistDescription(artist)) }
                        )
                    }
                }
            }

            // TRACKS displays the full local song catalogue.
            LibraryContentType.TRACKS -> {
                if (model.tracks.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
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
                        key = { track -> "track_grid_${track.id}" }
                    ) { track ->
                        val isPlaying by remember(track.id, playingTrackIdProvider) {
                            derivedStateOf { playingTrackIdProvider() == track.id }
                        }
                        LibraryTrackGridItem(
                            track = track,
                            isCurrentlyPlaying = isPlaying,
                            onClick = { onEvent(LibraryUiEvent.PlayTrack(track)) },
                            onPlayNextClick = { onEvent(LibraryUiEvent.PlayNext(track)) },
                            onAddToQueueClick = { onEvent(LibraryUiEvent.AddToQueue(track)) },
                            onAddToPlaylistClick = { onEvent(LibraryUiEvent.ShowPlaylistPicker(track)) },
                            onGoToAlbumClick = { onEvent(LibraryUiEvent.OpenTrackAlbum(track)) },
                            onGoToArtistClick = { onEvent(LibraryUiEvent.OpenTrackArtist(track)) }
                        )
                    }
                }
            }
        }
    }
}
