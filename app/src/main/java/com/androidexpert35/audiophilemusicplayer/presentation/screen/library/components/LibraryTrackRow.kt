package com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioFormat
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track
import com.androidexpert35.audiophilemusicplayer.presentation.screen.common.components.TrackOptionsMenu
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileMusicPlayerTheme
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophilePrimary

/**
 * Compact row showing a playable track within the library catalogue.
 *
 * @param track Track rendered in the row.
 * @param isCurrentlyPlaying Whether this row represents the track currently loaded in playback.
 * @param isLiked Whether this track is in the user's liked-songs collection.
 * @param showAlbumTitle Whether the subtitle should include the album title.
 * @param onClick Callback invoked when the row is tapped to start playback.
 * @param onLikeClick Callback invoked when the heart icon is tapped to toggle the like state.
 * @param onAddToPlaylistClick Callback invoked after the user selects “Add to playlist”.
 * @param onPlayNextClick Callback inserting the track immediately after the active item.
 * @param onAddToQueueClick Callback appending the track to the active playback queue.
 * @param onGoToAlbumClick Callback opening the track's album, or `null` when unavailable or redundant.
 * @param onGoToArtistClick Callback opening the track's artist, or `null` when unavailable or redundant.
 * @param modifier Optional [Modifier] for the root surface.
 */
@Composable
internal fun LibraryTrackRow(
    track: Track,
    modifier: Modifier = Modifier,
    isCurrentlyPlaying: Boolean = false,
    isLiked: Boolean = false,
    showAlbumTitle: Boolean = true,
    onClick: () -> Unit,
    onLikeClick: () -> Unit = {},
    onAddToPlaylistClick: (() -> Unit)? = null,
    onPlayNextClick: (() -> Unit)? = null,
    onAddToQueueClick: (() -> Unit)? = null,
    onGoToAlbumClick: (() -> Unit)? = null,
    onGoToArtistClick: (() -> Unit)? = null
) {
    // Fully opaque primaryContainer eliminates the semi-transparent GPU blend
    // overhead that copy(alpha = 0.42f) would cause for every visible list item
    // during a scroll pass.
    val containerColor = if (isCurrentlyPlaying) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainer
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = containerColor,
        tonalElevation = 2.dp,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TrackAlbumArtwork(
                track = track,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.size(40.dp),
                fallbackIconSize = 20.dp
            )

            // Memoize all string-building operations keyed on the track id so they
            // run once per track, not on every recomposition triggered by playback
            // state or liked-state changes while the list is scrolling.
            val subtitleText = remember(track.id, track.artistName, track.albumTitle, showAlbumTitle) {
                if (showAlbumTitle) "${track.artistName} • ${track.albumTitle}" else track.artistName
            }
            val discLabel = if (track.discNumber > 1) {
                stringResource(R.string.library_disc_label, track.discNumber)
            } else {
                null
            }
            val trackNumberLabel = if (track.trackNumber > 0) {
                stringResource(R.string.library_track_number_label, track.trackNumber)
            } else {
                null
            }
            val metadataText = remember(track.id, discLabel, trackNumberLabel, track.durationMs) {
                buildList {
                    discLabel?.let(::add)
                    trackNumberLabel?.let(::add)
                    add(formatTrackDuration(track.durationMs))
                }.joinToString(separator = " • ")
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitleText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = metadataText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Heart icon — always shown; filled when liked, outlined when not
            IconButton(
                onClick = onLikeClick,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = if (isLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = if (isLiked) {
                        stringResource(R.string.cd_unlike_track)
                    } else {
                        stringResource(R.string.cd_like_track)
                    },
                    tint = if (isLiked) AudiophilePrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            TrackOptionsMenu(
                onPlayNext = onPlayNextClick,
                onAddToQueue = onAddToQueueClick,
                onAddToPlaylist = onAddToPlaylistClick,
                onGoToAlbum = onGoToAlbumClick,
                onGoToArtist = onGoToArtistClick
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF101114)
@Composable
private fun LibraryTrackRowPreview() {
    AudiophileMusicPlayerTheme {
        LibraryTrackRow(
            track = Track(
                id = 1L,
                title = "So What",
                artistName = "Miles Davis",
                albumTitle = "Kind of Blue",
                albumId = 7L,
                durationMs = 565_000L,
                uri = "content://tracks/1",
                trackNumber = 1,
                discNumber = 1,
                audioFormat = AudioFormat.UNKNOWN,
                fileSizeBytes = 34_000_000L,
                dateAdded = 0L
            ),
            onClick = {},
            onLikeClick = {},
            isLiked = true,
            showAlbumTitle = true,
            isCurrentlyPlaying = true,
            modifier = Modifier.padding(16.dp)
        )
    }
}
