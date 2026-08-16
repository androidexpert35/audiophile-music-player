package com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioFormat
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track
import com.androidexpert35.audiophilemusicplayer.presentation.screen.common.components.TrackOptionsMenu
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileMusicPlayerTheme

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
            TrackArtworkCard(
                position = index + 1,
                track = track,
                isCurrentlyPlaying = currentPlayingTrackId == track.id,
                onClick = { onTrackClick(track) }
            )
        }
    }
}

/**
 * Reusable artwork-first tile for artist popular songs and Library track grids.
 *
 * Renders album artwork with a music-note fallback, an optional rank badge and
 * contextual track menu, then title, optional artist name, duration, and the
 * same HD badge used by the artist's popular-songs row for lossless tracks.
 *
 * @param track Track whose metadata and artwork are rendered.
 * @param isCurrentlyPlaying Whether this tile represents the active playback track.
 * @param onClick Callback invoked when the tile is tapped.
 * @param modifier Optional [Modifier] for the root [Surface].
 * @param position Optional 1-based rank shown in the artwork's top-left corner.
 * @param cardWidth Fixed card width for horizontal rows; `null` fills a grid cell.
 * @param artworkHeight Fixed artwork height for horizontal rows; `null` produces square art.
 * @param showArtistName Whether to show the performer below the title.
 * @param showDuration Whether to show the track duration below the title.
 * @param onPlayNextClick Optional callback inserting the track after the active item.
 * @param onAddToQueueClick Optional callback adding the track to the queue.
 * @param onAddToPlaylistClick Optional callback opening the playlist picker.
 * @param onGoToAlbumClick Optional callback opening the track album.
 * @param onGoToArtistClick Optional callback opening the track artist.
 * @param actionMenuIconTint Optional tint for the card's three-dots action trigger.
 */
@Composable
internal fun TrackArtworkCard(
    track: Track,
    isCurrentlyPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    position: Int? = null,
    cardWidth: Dp? = TrackCardWidth,
    artworkHeight: Dp? = TrackCardArtHeight,
    showArtistName: Boolean = false,
    showDuration: Boolean = true,
    onPlayNextClick: (() -> Unit)? = null,
    onAddToQueueClick: (() -> Unit)? = null,
    onAddToPlaylistClick: (() -> Unit)? = null,
    onGoToAlbumClick: (() -> Unit)? = null,
    onGoToArtistClick: (() -> Unit)? = null,
    actionMenuIconTint: Color? = null,
) {
    Surface(
        modifier = (if (cardWidth != null) modifier.width(cardWidth) else modifier)
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
                    .then(
                        if (artworkHeight != null) {
                            Modifier.height(artworkHeight)
                        } else {
                            Modifier.aspectRatio(1f)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                TrackAlbumArtwork(
                    track = track,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxSize()
                )

                // Position badge — top-left corner
                position?.let { rank ->
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
                                text = rank.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                TrackOptionsMenu(
                    onPlayNext = onPlayNextClick,
                    onAddToQueue = onAddToQueueClick,
                    onAddToPlaylist = onAddToPlaylistClick,
                    onGoToAlbum = onGoToAlbumClick,
                    onGoToArtist = onGoToArtistClick,
                    iconTint = actionMenuIconTint,
                    modifier = Modifier.align(Alignment.TopEnd),
                )

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
                if (showArtistName) {
                    Text(
                        text = track.artistName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (showDuration || track.audioFormat.isLossless) {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (showDuration) {
                            Text(
                                text = formatTrackDuration(track.durationMs),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
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

