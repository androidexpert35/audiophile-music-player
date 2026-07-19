package com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.presentation.theme.paddingMedium

/**
 * Presents a playlist hero with the same elevated artwork language as album overview.
 *
 * @param name Playlist title.
 * @param albumArtUris Artwork for the playlist's recent tracks, used to build the hero mosaic.
 * @param trackCount Number of songs in the M3U playlist.
 * @param totalDurationMs Sum of the playable song durations.
 * @param unavailableTrackCount Number of playlist entries absent from the local media index.
 * @param modifier Optional modifier applied to the header.
 */
@Composable
internal fun PlaylistDetailHeader(
    name: String,
    albumArtUris: List<String>,
    trackCount: Int,
    totalDurationMs: Long,
    unavailableTrackCount: Int,
    modifier: Modifier = Modifier
) {
    val durationLabel = formatPlaylistDuration(totalDurationMs)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            // The playlist search bar is 16 dp taller than the album's top bar because
            // it has its own vertical inset. Reserve that height plus a 12 dp visual gap.
            .padding(top = PLAYLIST_HERO_TOP_INSET)
    ) {
        DetailHeroArtwork {
            PlaylistArtworkMosaic(
                albumArtUris = albumArtUris,
                modifier = Modifier.fillMaxSize()
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = paddingMedium, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                // The title sits against the app surface rather than the mosaic, so the
                // semantic on-surface token always keeps it legible in either colour scheme.
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = pluralStringResource(R.plurals.playlist_track_count, trackCount, trackCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "•",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = durationLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (unavailableTrackCount > 0) {
                Text(
                    text = pluralStringResource(
                        R.plurals.playlist_unavailable_track_count,
                        unavailableTrackCount,
                        unavailableTrackCount
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

private val PLAYLIST_HERO_TOP_INSET = 84.dp

/** Formats a playlist total as minutes or hours and minutes using localised resources. */
@Composable
private fun formatPlaylistDuration(durationMs: Long): String {
    val totalMinutes = (durationMs / 60_000L).coerceAtLeast(0L)
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return if (hours > 0L) {
        stringResource(R.string.playlist_duration_hours, hours, minutes)
    } else {
        stringResource(R.string.playlist_duration_minutes, minutes)
    }
}
