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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Album
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileMusicPlayerTheme
import kotlin.math.ceil

/** Fixed row height for one row of album grid cards (square art + text area). */
private val AlbumCardArtSize = 152.dp

/** Text area below the album artwork. */
private val AlbumCardTextHeight = 52.dp

/** Total card height: square art (width ≈ AlbumCardArtSize) + text area. */
private val AlbumCardTotalHeight = AlbumCardArtSize + AlbumCardTextHeight

/** Spacing between rows and columns in the 2-column grid. */
private val GridSpacing = 12.dp

/**
 * 2-column album grid for the artist description screen.
 *
 * Because `LazyVerticalGrid` cannot be arbitrarily nested inside a lazy column,
 * this composable wraps the grid in a [Box] whose height is computed from the
 * number of album rows so the outer scroll container has a concrete measurement.
 * Scrolling is disabled on the inner grid; the parent lazy column handles all
 * vertical scroll interaction.
 *
 * @param albums Ordered list of albums to display in the grid (newest first).
 * @param onAlbumClick Callback invoked when an album card is tapped, carrying the album ID.
 * @param modifier Optional [Modifier] for the outer sizing [Box].
 */
@Composable
internal fun ArtistDescriptionAlbumsSection(
    albums: List<Album>,
    onAlbumClick: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    if (albums.isEmpty()) return

    val rowCount = ceil(albums.size / 2.0).toInt()

    // Compute the deterministic grid height so the LazyColumn can measure it.
    // rowCount rows of AlbumCardTotalHeight each, with GridSpacing between rows.
    val gridHeight = AlbumCardTotalHeight * rowCount + GridSpacing * (rowCount - 1).coerceAtLeast(0)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(gridHeight)
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(GridSpacing),
            verticalArrangement = Arrangement.spacedBy(GridSpacing),
            // The outer LazyColumn owns vertical scrolling; disable inner scrolling
            // to prevent conflicting nested-scroll gestures.
            userScrollEnabled = false
        ) {
            items(
                items = albums,
                key = { album -> album.id }
            ) { album ->
                ArtistAlbumGridCard(
                    album = album,
                    onClick = { onAlbumClick(album.id) }
                )
            }
        }
    }
}

/**
 * Single album card rendered in the 2-column discography grid.
 *
 * Shows a square album artwork (with a local or remote URL fallback), the
 * album title in bold, and the release year with track count below.
 *
 * @param album Album whose metadata and artwork are rendered.
 * @param onClick Callback invoked when the card is tapped.
 * @param modifier Optional [Modifier] for the root [Surface].
 */
@Composable
private fun ArtistAlbumGridCard(
    album: Album,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Surface(
        modifier = modifier
            .fillMaxWidth()
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
                // Prefer the remote Deezer URL; fall back to local MediaStore URI.
                val artData = album.remoteArtUrl ?: album.artUri

                // Icon placeholder — always drawn first; the AsyncImage overlays it
                // when album art loads successfully.
                Icon(
                    imageVector = Icons.Filled.Album,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
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
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildList {
                        if (album.year > 0) add(album.year.toString())
                        add(
                            pluralStringResource(
                                R.plurals.library_track_count,
                                album.trackCount,
                                album.trackCount
                            )
                        )
                    }.joinToString(separator = " • "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF090B10, name = "ArtistDescriptionAlbumsSection")
@Composable
private fun ArtistDescriptionAlbumsSectionPreview() {
    AudiophileMusicPlayerTheme {
        val previewAlbums = listOf(
            Album(id = 1L, title = "Kind of Blue", artistName = "Miles Davis", artUri = null, trackCount = 5, year = 1959),
            Album(id = 2L, title = "Bitches Brew", artistName = "Miles Davis", artUri = null, trackCount = 4, year = 1970),
            Album(id = 3L, title = "Sketches of Spain", artistName = "Miles Davis", artUri = null, trackCount = 6, year = 1960)
        )
        ArtistDescriptionAlbumsSection(
            albums = previewAlbums,
            onAlbumClick = {}
        )
    }
}

