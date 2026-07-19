package com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.presentation.screen.common.components.TrackOptionsMenu
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileMusicPlayerTheme
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophilePrimary

/**
 * Primary action controls row for the album detail screen.
 *
 * Structured into two visual zones to establish a clear hierarchy:
 * - **Left zone**: album like plus an overflow menu for adding the complete album
 *   to a playlist, scheduling it next, or appending it to the queue.
 * - **Right zone**: shuffle toggle and the dominant, large primary play circle.
 *
 * The large play button uses the app's [AudiophilePrimary] accent colour so it
 * anchors the user's eye immediately after the hero artwork.
 *
 * @param isLiked Whether the album is currently in the user's liked / saved collection.
 * @param onPlayClick Callback starting sequential album playback from the first track.
 * @param onShuffleClick Callback starting a shuffled album playback session.
 * @param onLikeClick Callback toggling the liked / saved state for this album.
 * @param onPlayNextClick Callback inserting the complete album after the active item.
 * @param onAddToQueueClick Callback appending the complete album to the queue.
 * @param onAddToPlaylistClick Callback opening the playlist destination selector.
 * @param modifier Optional [Modifier] applied to the root [Row].
 */
@Composable
internal fun AlbumDetailActionRow(
    isLiked: Boolean,
    onPlayClick: () -> Unit,
    onShuffleClick: () -> Unit,
    onLikeClick: () -> Unit,
    onPlayNextClick: () -> Unit,
    onAddToQueueClick: () -> Unit,
    onAddToPlaylistClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ── Left zone: secondary icon actions ────────────────────────────────
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Heart / like toggle — filled when saved, outlined when not
            IconButton(onClick = onLikeClick) {
                Icon(
                    imageVector = if (isLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = if (isLiked) {
                        stringResource(R.string.cd_unlike_track)
                    } else {
                        stringResource(R.string.cd_like_track)
                    },
                    tint = if (isLiked) AudiophilePrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }

            TrackOptionsMenu(
                onPlayNext = onPlayNextClick,
                onAddToQueue = onAddToQueueClick,
                onAddToPlaylist = onAddToPlaylistClick
            )
        }

        // Spacer pushes the play controls to the trailing edge.
        Spacer(modifier = Modifier.weight(1f))

        // ── Right zone: shuffle + primary play button ─────────────────────────
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Shuffle — same visual weight as the secondary icons on the left
            IconButton(onClick = onShuffleClick) {
                Icon(
                    imageVector = Icons.Filled.Shuffle,
                    contentDescription = stringResource(R.string.album_detail_shuffle_content_description),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }

            // Large primary circular play button — the dominant visual anchor for this row.
            // Uses AudiophilePrimary fill to draw the eye and signal the primary action.
            Surface(
                onClick = onPlayClick,
                shape = CircleShape,
                color = AudiophilePrimary,
                modifier = Modifier.size(56.dp),
                shadowElevation = 12.dp,
                tonalElevation = 0.dp
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = stringResource(R.string.album_overview_play_album_label),
                        // Use surfaceContainerHighest for maximum contrast against AudiophilePrimary.
                        tint = MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@Preview(
    name = "AlbumDetailActionRow — Not Liked",
    showBackground = true,
    backgroundColor = 0xFF090B10
)
@Composable
private fun AlbumDetailActionRowNotLikedPreview() {
    AudiophileMusicPlayerTheme {
        AlbumDetailActionRow(
            isLiked = false,
            onPlayClick = {},
            onShuffleClick = {},
            onLikeClick = {},
            onPlayNextClick = {},
            onAddToQueueClick = {},
            onAddToPlaylistClick = {}
        )
    }
}

@Preview(
    name = "AlbumDetailActionRow — Liked",
    showBackground = true,
    backgroundColor = 0xFF090B10
)
@Composable
private fun AlbumDetailActionRowLikedPreview() {
    AudiophileMusicPlayerTheme {
        AlbumDetailActionRow(
            isLiked = true,
            onPlayClick = {},
            onShuffleClick = {},
            onLikeClick = {},
            onPlayNextClick = {},
            onAddToQueueClick = {},
            onAddToPlaylistClick = {}
        )
    }
}
