package com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioCodec
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioFormat
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Album
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileMusicPlayerTheme
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library.AlbumOverviewUiModel

/**
 * Hero summary card shown at the top of the album overview screen.
 *
 * Presents album artwork, high-value metadata, and the primary playback action
 * before the user reaches the track list. Back navigation is handled by the
 * shared `AppTopBar` rendered above this card.
 *
 * @param model Current immutable album overview state.
 * @param onPlayAlbumClick Callback starting album playback from the first track.
 * @param modifier Optional [Modifier] for the root surface.
 */
@Composable
internal fun AlbumOverviewSummaryCard(
    model: AlbumOverviewUiModel,
    onPlayAlbumClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val album = model.album ?: return
    val context = LocalContext.current

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 8.dp,
        shadowElevation = 12.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    if (album.artUri != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(album.artUri)
                                .crossfade(250)
                                .build(),
                            contentDescription = stringResource(R.string.cd_album_art, album.title),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Album,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(34.dp)
                        )
                    }
                }

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = album.title,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = album.artistName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = buildList {
                            add(
                                pluralStringResource(
                                    R.plurals.library_track_count,
                                    model.tracks.size,
                                    model.tracks.size
                                )
                            )
                            if (album.year > 0) {
                                add(album.year.toString())
                            }
                        }.joinToString(separator = " • "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Button(
                onClick = onPlayAlbumClick,
                modifier = Modifier.fillMaxWidth(),
                enabled = model.tracks.isNotEmpty()
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(text = stringResource(R.string.album_overview_play_album_label))
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AlbumFactChip(
                    label = stringResource(R.string.album_overview_fact_duration),
                    value = formatTrackDuration(model.totalDurationMs)
                )
                AlbumFactChip(
                    label = stringResource(R.string.album_overview_fact_storage),
                    value = formatByteCount(model.totalSizeBytes)
                )
                AlbumFactChip(
                    label = stringResource(R.string.album_overview_fact_formats),
                    value = model.codecSummary
                )
                if (album.year > 0) {
                    AlbumFactChip(
                        label = stringResource(R.string.album_overview_fact_year),
                        value = album.year.toString()
                    )
                }
            }
        }
    }
}

@Composable
private fun AlbumFactChip(
    label: String,
    value: String
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF101114)
@Composable
private fun AlbumOverviewSummaryCardPreview() {
    AudiophileMusicPlayerTheme {
        AlbumOverviewSummaryCard(
            model = AlbumOverviewUiModel(                album = Album(
                    id = 10L,
                    title = "Kind of Blue",
                    artistName = "Miles Davis",
                    artUri = null,
                    trackCount = 5,
                    year = 1959
                ),
                tracks = listOf(
                    Track(
                        id = 1L,
                        title = "So What",
                        artistName = "Miles Davis",
                        albumTitle = "Kind of Blue",
                        albumId = 10L,
                        durationMs = 545_000L,
                        uri = "content://tracks/1",
                        trackNumber = 1,
                        discNumber = 1,
                        audioFormat = AudioFormat(
                            sampleRateHz = 96_000,
                            bitDepth = 24,
                            channelCount = 2,
                            codec = AudioCodec.FLAC,
                            isLossless = true
                        ),
                        fileSizeBytes = 45_000_000L,
                        dateAdded = 0L
                    )
                ),
                totalDurationMs = 2_631_000L,
                totalSizeBytes = 220_000_000L,
                discCount = 1,
                losslessTrackCount = 5,
                hiResTrackCount = 5,
                maxSampleRateHz = 96_000,
                maxBitDepth = 24,
                codecSummary = "FLAC"
            ),
            onPlayAlbumClick = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}

