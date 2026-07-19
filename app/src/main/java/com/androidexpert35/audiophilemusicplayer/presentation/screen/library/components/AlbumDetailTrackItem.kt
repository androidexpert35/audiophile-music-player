package com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
 * Compact track row for the album detail screen tracklist.
 *
 * Follows a Spotify-style three-zone layout:
 * - **Left (28 dp fixed)**: track position number, or an [Icons.Filled.Equalizer] icon
 *   tinted with the primary accent when the track is currently loaded in the playback
 *   engine. This provides an at-a-glance now-playing indicator without a separate badge.
 * - **Centre (weight 1)**: track title rendered in the primary accent colour when
 *   active (normal `onSurface` otherwise), plus the artist name as a secondary subtitle.
 * - **Right**: formatted MM:SS duration and a three-dot options icon button that
 *   surfaces track-level actions (add to queue, share, etc.).
 *
 * A subtle [HorizontalDivider] is drawn beneath the row to visually separate tracks
 * without heavy card chrome, keeping the tracklist lean and scannable.
 *
 * @param track Track data to render in the row.
 * @param trackNumber 1-based display position within the current disc or album.
 * @param isCurrentlyPlaying Whether this track is currently loaded in the playback engine.
 * @param onClick Callback invoked when the user taps the row to start playback.
 * @param onPlayNextClick Callback inserting the track immediately after the active item.
 * @param onAddToQueueClick Callback appending the track to the active playback queue.
 * @param onAddToPlaylistClick Callback opening the playlist destination selector.
 * @param onGoToArtistClick Callback opening the track's artist, or `null` when unavailable.
 *   "Go to album" is intentionally omitted here — every row already belongs to the album
 *   currently on screen, so that destination would be redundant.
 * @param modifier Optional [Modifier] applied to the root [Column].
 */
@Composable
internal fun AlbumDetailTrackItem(
    track: Track,
    trackNumber: Int,
    isCurrentlyPlaying: Boolean,
    onClick: () -> Unit,
    onPlayNextClick: () -> Unit,
    onAddToQueueClick: () -> Unit,
    onAddToPlaylistClick: () -> Unit,
    onGoToArtistClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // Active tracks receive the primary accent; inactive tracks use the standard surface colour.
    val titleColor = if (isCurrentlyPlaying) AudiophilePrimary
    else MaterialTheme.colorScheme.onSurface

    // Memoize formatted duration — String.format allocates on every call.
    val durationText = remember(track.durationMs) { formatTrackDuration(track.durationMs) }

    // Capture the token before remember — MaterialTheme.colorScheme is @Composable and
    // cannot be accessed inside a non-composable lambda body.
    val outlineVariantColor = MaterialTheme.colorScheme.outlineVariant
    // Pre-compute the divider color once per theme change to avoid copy(alpha)
    // allocating a new Color object on every recomposition of each track row.
    val dividerColor = remember(outlineVariantColor) { outlineVariantColor.copy(alpha = 0.4f) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── Left: track number or equalizer playing indicator ─────────────
            Box(
                modifier = Modifier.width(28.dp),
                contentAlignment = Alignment.Center
            ) {
                if (isCurrentlyPlaying) {
                    Icon(
                        imageVector = Icons.Filled.Equalizer,
                        contentDescription = stringResource(R.string.album_detail_now_playing_content_description),
                        tint = AudiophilePrimary,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Text(
                        text = trackNumber.toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Normal
                    )
                }
            }

            // ── Centre: title + artist ────────────────────────────────────────
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track.artistName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // ── Right: duration + three-dot options ───────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                Text(
                    text = durationText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TrackOptionsMenu(
                    onPlayNext = onPlayNextClick,
                    onAddToQueue = onAddToQueueClick,
                    onAddToPlaylist = onAddToPlaylistClick,
                    onGoToArtist = onGoToArtistClick
                )
            }
        }

        // Subtle divider separates tracks without heavy card borders.
        HorizontalDivider(
            modifier = Modifier.padding(start = 56.dp, end = 16.dp),
            color = dividerColor
        )
    }
}

// ---------------------------------------------------------------------------
// Previews
// ---------------------------------------------------------------------------

@Preview(
    name = "AlbumDetailTrackItem — Normal",
    showBackground = true,
    backgroundColor = 0xFF090B10
)
@Composable
private fun AlbumDetailTrackItemNormalPreview() {
    AudiophileMusicPlayerTheme {
        AlbumDetailTrackItem(
            track = Track(
                id = 1L,
                title = "So What",
                artistName = "Miles Davis",
                albumTitle = "Kind of Blue",
                albumId = 1L,
                durationMs = 545_000L,
                uri = "content://tracks/1",
                trackNumber = 1,
                discNumber = 1,
                audioFormat = AudioFormat.UNKNOWN,
                fileSizeBytes = 45_000_000L,
                dateAdded = 0L
            ),
            trackNumber = 1,
            isCurrentlyPlaying = false,
            onClick = {},
            onPlayNextClick = {},
            onAddToQueueClick = {},
            onAddToPlaylistClick = {}
        )
    }
}

@Preview(
    name = "AlbumDetailTrackItem — Playing",
    showBackground = true,
    backgroundColor = 0xFF090B10
)
@Composable
private fun AlbumDetailTrackItemPlayingPreview() {
    AudiophileMusicPlayerTheme {
        AlbumDetailTrackItem(
            track = Track(
                id = 2L,
                title = "Freddie Freeloader",
                artistName = "Miles Davis",
                albumTitle = "Kind of Blue",
                albumId = 1L,
                durationMs = 578_000L,
                uri = "content://tracks/2",
                trackNumber = 2,
                discNumber = 1,
                audioFormat = AudioFormat.UNKNOWN,
                fileSizeBytes = 48_000_000L,
                dateAdded = 0L
            ),
            trackNumber = 2,
            isCurrentlyPlaying = true,
            onClick = {},
            onPlayNextClick = {},
            onAddToQueueClick = {},
            onAddToPlaylistClick = {}
        )
    }
}
