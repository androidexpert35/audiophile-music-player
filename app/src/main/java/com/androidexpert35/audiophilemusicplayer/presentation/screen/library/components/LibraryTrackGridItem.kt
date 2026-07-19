package com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioFormat
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track
import com.androidexpert35.audiophilemusicplayer.presentation.screen.common.components.TrackOptionsMenu
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileMusicPlayerTheme

/**
 * Two-column grid item for a track displayed in the library grid view.
 *
 * Shows a square music-note placeholder (track-level artwork requires an additional
 * resolver query which would degrade grid scroll performance), the track title bold,
 * and the artist name below it. Designed for a [androidx.compose.foundation.lazy.grid.LazyVerticalGrid]
 * with two equal-width columns.
 *
 * @param track Track rendered in this grid cell.
 * @param isCurrentlyPlaying Whether this cell represents the track currently loaded in playback.
 * @param onClick Invoked when the user taps the cell.
 * @param onPlayNextClick Callback inserting the track immediately after the active item.
 * @param onAddToQueueClick Callback appending the track to the active playback queue.
 * @param onAddToPlaylistClick Callback opening the playlist destination selector.
 * @param onGoToAlbumClick Callback opening the track's album, or `null` when unavailable or redundant.
 * @param onGoToArtistClick Callback opening the track's artist, or `null` when unavailable or redundant.
 * @param modifier Optional [Modifier] for the root column.
 */
@Composable
internal fun LibraryTrackGridItem(
    track: Track,
    isCurrentlyPlaying: Boolean = false,
    onClick: () -> Unit,
    onPlayNextClick: () -> Unit,
    onAddToQueueClick: () -> Unit,
    onAddToPlaylistClick: () -> Unit,
    onGoToAlbumClick: (() -> Unit)? = null,
    onGoToArtistClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val backgroundTint = if (isCurrentlyPlaying) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(backgroundTint)
            .clickable(onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Square music-note placeholder — fills column width while preserving 1:1 ratio
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(48.dp)
            )
            TrackOptionsMenu(
                onPlayNext = onPlayNextClick,
                onAddToQueue = onAddToQueueClick,
                onAddToPlaylist = onAddToPlaylistClick,
                onGoToAlbum = onGoToAlbumClick,
                onGoToArtist = onGoToArtistClick,
                modifier = Modifier.align(Alignment.TopEnd)
            )
        }

        Text(
            text = track.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = track.artistName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF10141D, name = "LibraryTrackGridItem – preview")
@Composable
private fun LibraryTrackGridItemPreview() {
    AudiophileMusicPlayerTheme {
        LibraryTrackGridItem(
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
            onPlayNextClick = {},
            onAddToQueueClick = {},
            onAddToPlaylistClick = {},
            modifier = Modifier.padding(8.dp)
        )
    }
}
