package com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileMusicPlayerTheme
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library.LibraryContentType
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library.LibraryUiModel

/**
 * Rich header surface for the library screen.
 *
 * Provides collection summary pills and section picker chips in one compact
 * card anchoring the catalogue experience. The screen title and quick actions
 * (refresh, settings) live in the shared top bar above this card.
 * Track-level search is available through the dedicated Search destination.
 *
 * @param model Current immutable library snapshot.
 * @param onSelectContentType Callback selecting the active catalogue section.
 * @param modifier Optional [Modifier] for the root card.
 */
@Composable
internal fun LibraryHeaderCard(
    model: LibraryUiModel,
    onSelectContentType: (LibraryContentType) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 8.dp,
        shadowElevation = 12.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SummaryPill(
                    label = stringResource(R.string.library_tracks_section_label),
                    value = model.tracks.size.toString()
                )
                SummaryPill(
                    label = stringResource(R.string.library_albums_section_label),
                    value = model.albums.size.toString()
                )
                SummaryPill(
                    label = stringResource(R.string.library_artists_section_label),
                    value = model.artists.size.toString()
                )
            }

            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LibraryContentType.entries.forEach { contentType ->
                    FilterChip(
                        selected = model.selectedContentType == contentType,
                        onClick = { onSelectContentType(contentType) },
                        label = {
                            Text(
                                text = when (contentType) {
                                    LibraryContentType.TRACKS -> stringResource(R.string.library_tracks_section_label)
                                    LibraryContentType.PLAYLISTS -> stringResource(R.string.library_playlists_section_label)
                                    LibraryContentType.ALBUMS -> stringResource(R.string.library_albums_section_label)
                                    LibraryContentType.ARTISTS -> stringResource(R.string.library_artists_section_label)
                                }
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryPill(
    label: String,
    value: String
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.84f)
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF101114)
@Composable
private fun LibraryHeaderCardPreview() {
    AudiophileMusicPlayerTheme {
        LibraryHeaderCard(
            model = LibraryUiModel(
                selectedContentType = LibraryContentType.TRACKS,
                tracks = List(24) { index ->
                    com.androidexpert35.audiophilemusicplayer.domain.model.track.Track(
                        id = index.toLong(),
                        title = "Track $index",
                        artistName = "Artist",
                        albumTitle = "Album",
                        albumId = 10L,
                        durationMs = 320000L,
                        uri = "content://tracks/$index",
                        trackNumber = index + 1,
                        discNumber = 1,
                        audioFormat = com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioFormat.UNKNOWN,
                        fileSizeBytes = 10_000_000L,
                        dateAdded = 0L
                    )
                },
                albums = List(8) { index ->
                    com.androidexpert35.audiophilemusicplayer.domain.model.track.Album(
                        id = index.toLong(),
                        title = "Album $index",
                        artistName = "Artist",
                        artUri = null,
                        trackCount = 9,
                        year = 2024
                    )
                },
                artists = List(5) { index ->
                    com.androidexpert35.audiophilemusicplayer.domain.model.track.Artist(
                        id = index.toLong(),
                        name = "Artist $index",
                        albumCount = 2,
                        trackCount = 18
                    )
                }
            ),
            onSelectContentType = {}
        )
    }
}
