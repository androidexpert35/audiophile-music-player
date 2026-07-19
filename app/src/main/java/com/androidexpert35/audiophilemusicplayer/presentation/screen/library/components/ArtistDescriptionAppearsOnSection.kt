package com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Album
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileMusicPlayerTheme

/** Width of each album tile in the "Appears On" horizontal scroll. */
private val AppearsOnCardWidth = 140.dp

/**
 * Horizontally scrollable row of album tiles for the artist description
 * "Appears On" section.
 *
 * These are albums where the artist appears as a featured credit but is not the
 * primary album artist (e.g. "Album by Artist X feat. TargetArtist"). Tapping a
 * tile opens the corresponding album overview screen.
 *
 * The row uses a [LazyRow] with stable keys derived from album IDs so that
 * scroll position and recomposition are efficient.
 *
 * @param albums Ordered list of featured-appearance albums (newest first).
 * @param onAlbumClick Callback invoked when a tile is tapped, carrying the album ID.
 * @param modifier Optional [Modifier] applied to the root [LazyRow].
 */
@Composable
internal fun ArtistDescriptionAppearsOnSection(
    albums: List<Album>,
    onAlbumClick: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(
            items = albums,
            key = { album -> album.id }
        ) { album ->
            ArtistAppearsOnCard(
                album = album,
                onClick = { onAlbumClick(album.id) }
            )
        }
    }
}

/**
 * Single album tile used in the "Appears On" horizontal row.
 *
 * Renders a square album artwork at the top and the album title plus primary artist
 * name below, surfacing enough context for the user to identify collaborator albums.
 *
 * @param album Album whose metadata and artwork are rendered.
 * @param onClick Callback invoked when the tile is tapped.
 * @param modifier Optional [Modifier] for the root [Surface].
 */
@Composable
private fun ArtistAppearsOnCard(
    album: Album,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Surface(
        modifier = modifier
            .width(AppearsOnCardWidth)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
        shadowElevation = 4.dp
    ) {
        Column {
            // ── Square album artwork ──────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                val artData = album.remoteArtUrl ?: album.artUri

                // Icon placeholder — always drawn first; the AsyncImage overlays it
                // when the album art loads successfully.
                Icon(
                    imageVector = Icons.Filled.Album,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.55f)
                )

                if (artData != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(artData)
                            .crossfade(200)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // ── Album metadata ────────────────────────────────────────────────
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = album.title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = album.artistName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (album.year > 0) {
                    Text(
                        text = album.year.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF090B10, name = "ArtistDescriptionAppearsOnSection")
@Composable
private fun ArtistDescriptionAppearsOnSectionPreview() {
    AudiophileMusicPlayerTheme {
        val previewAlbums = listOf(
            Album(id = 10L, title = "Random Access Memories", artistName = "Daft Punk", artUri = null, trackCount = 13, year = 2013),
            Album(id = 11L, title = "Currents", artistName = "Tame Impala", artUri = null, trackCount = 13, year = 2015),
            Album(id = 12L, title = "To Pimp a Butterfly", artistName = "Kendrick Lamar", artUri = null, trackCount = 16, year = 2015)
        )
        ArtistDescriptionAppearsOnSection(
            albums = previewAlbums,
            onAlbumClick = {}
        )
    }
}

