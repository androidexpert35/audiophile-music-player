package com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Album
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileMusicPlayerTheme

/**
 * Two-column grid item for an album displayed in the library grid view.
 *
 * Renders a large square album artwork thumbnail with the album title (bold)
 * and artist name centred below. Falls back to an album icon placeholder when
 * no artwork URI is available. Designed for [androidx.compose.foundation.lazy.grid.LazyVerticalGrid]
 * with two equal-width columns.
 *
 * @param album Album rendered in this grid cell.
 * @param onClick Invoked when the user taps the cell.
 * @param modifier Optional [Modifier] for the root column.
 */
@Composable
internal fun LibraryAlbumGridItem(
    album: Album,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Memoize the ImageRequest keyed on the art URIs so that scroll-driven
    // recompositions do not rebuild the request object and cancel in-flight loads.
    val imageRequest = remember(album.artUri, album.remoteArtUrl) {
        ImageRequest.Builder(context)
            .data(album.remoteArtUrl ?: album.artUri)
            .crossfade(200)
            .build()
    }

    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Square artwork thumbnail — fills column width while preserving 1:1 ratio
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            if (!album.artUri.isNullOrBlank() || !album.remoteArtUrl.isNullOrBlank()) {
                AsyncImage(
                    model = imageRequest,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Album,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(48.dp)
                )
            }
        }

        Text(
            text = album.title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = album.artistName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF10141D, name = "LibraryAlbumGridItem – no art")
@Composable
private fun LibraryAlbumGridItemPreview() {
    AudiophileMusicPlayerTheme {
        LibraryAlbumGridItem(
            album = Album(
                id = 1L,
                title = "Kind of Blue",
                artistName = "Miles Davis",
                artUri = null,
                trackCount = 5,
                year = 1959
            ),
            onClick = {},
            modifier = Modifier.padding(8.dp)
        )
    }
}

