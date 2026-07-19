package com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components

import android.content.ContentUris
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioFormat
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileMusicPlayerTheme
import com.androidexpert35.audiophilemusicplayer.presentation.theme.MotionTokens

/** Width of each track tile in the popular-songs horizontal scroll. */
private val TrackCardWidth = 152.dp

/** Height of the album-art portion of each popular-track tile. */
private val TrackCardArtHeight = 116.dp

/**
 * Horizontally scrollable row of compact track tiles for the artist description
 * "Popular Songs" section.
 *
 * Each tile shows the track's album artwork, a position badge, the track title,
 * and its duration. Tapping a tile starts immediate playback.
 *
 * The row uses a [LazyRow] with stable keys derived from track IDs so that
 * recompositions caused by now-playing highlight changes are minimal.
 *
 * @param tracks Ordered list of tracks to display (typically the 5 most recently added).
 * @param currentPlayingTrackId Identifier of the currently active playback track, used
 *   to apply a now-playing accent to the matching tile.
 * @param onTrackClick Callback invoked when a tile is tapped.
 * @param modifier Optional [Modifier] applied to the root [LazyRow].
 */
@Composable
internal fun ArtistDescriptionPopularTracksSection(
    tracks: List<Track>,
    currentPlayingTrackId: Long?,
    onTrackClick: (Track) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        itemsIndexed(
            items = tracks,
            key = { _, track -> track.id }
        ) { index, track ->
            ArtistPopularTrackCard(
                position = index + 1,
                track = track,
                isCurrentlyPlaying = currentPlayingTrackId == track.id,
                onClick = { onTrackClick(track) }
            )
        }
    }
}

/**
 * Single tile used in the artist "Popular Songs" horizontal row.
 *
 * Renders a square album-art thumbnail at the top, a position badge in the
 * top-left corner, and the track title plus duration below the image.
 *
 * @param position 1-based rank of the track in the popular list.
 * @param track Track whose metadata and artwork are rendered.
 * @param isCurrentlyPlaying Whether this tile represents the active playback track.
 * @param onClick Callback invoked when the tile is tapped.
 * @param modifier Optional [Modifier] for the root [Surface].
 */
@Composable
private fun ArtistPopularTrackCard(
    position: Int,
    track: Track,
    isCurrentlyPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Prefer the locally-cached artUri (populated for DSD/DSF embedded art).
    // Fall back to the MediaStore album content URI for standard FLAC/MP3/AAC
    // tracks whose albumId is a positive MediaStore row identifier.
    val albumArtData = remember(track.artUri, track.albumId) {
        when {
            !track.artUri.isNullOrBlank() -> track.artUri
            track.albumId > 0L -> ContentUris.withAppendedId(
                MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
                track.albumId
            )
            else -> null
        }
    }

    Surface(
        modifier = modifier
            .width(TrackCardWidth)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = if (isCurrentlyPlaying) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        tonalElevation = 2.dp,
        shadowElevation = 4.dp
    ) {
        Column {
            // ── Album art ─────────────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(TrackCardArtHeight)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                // Icon placeholder — always drawn first; the AsyncImage overlays it
                // when the local album art loads successfully.
                Icon(
                    imageVector = Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.55f)
                )

                if (albumArtData != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(albumArtData)
                            .crossfade(MotionTokens.DurationShort)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Position badge — top-left corner
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .size(22.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = position.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // Now-playing indicator badge — top-right corner
                if (isCurrentlyPlaying) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier
                                .size(18.dp)
                                .padding(2.dp)
                        )
                    }
                }
            }

            // ── Track metadata ────────────────────────────────────────────────
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = formatTrackDuration(track.durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (track.audioFormat.isLossless) {
                        Text(
                            text = stringResource(R.string.common_hd_badge),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF090B10, name = "ArtistDescriptionPopularTracksSection")
@Composable
private fun ArtistDescriptionPopularTracksSectionPreview() {
    AudiophileMusicPlayerTheme {
        val previewTracks = (1..5).map { index ->
            Track(
                id = index.toLong(),
                title = listOf("So What", "Freddie Freeloader", "Blue in Green", "All Blues", "Flamenco Sketches")[index - 1],
                artistName = "Miles Davis",
                albumTitle = "Kind of Blue",
                albumId = 7L,
                durationMs = (200_000L + index * 45_000L),
                uri = "content://tracks/$index",
                trackNumber = index,
                discNumber = 1,
                audioFormat = AudioFormat.UNKNOWN,
                fileSizeBytes = 30_000_000L,
                dateAdded = 0L
            )
        }
        ArtistDescriptionPopularTracksSection(
            tracks = previewTracks,
            currentPlayingTrackId = 2L,
            onTrackClick = {}
        )
    }
}

