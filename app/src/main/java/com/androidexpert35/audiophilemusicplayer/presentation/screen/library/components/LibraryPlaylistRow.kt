package com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library.PlaylistUiModel

/**
 * Renders a local playlist with a compact mosaic of its four newest album covers.
 *
 * @param playlist Presentation-ready playlist summary.
 * @param onClick Invoked when the playlist is selected.
 * @param modifier Optional modifier for the list row.
 */
@Composable
internal fun LibraryPlaylistRow(
    playlist: PlaylistUiModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PlaylistArtworkMosaic(
                albumArtUris = playlist.albumArtUris,
                modifier = Modifier.size(56.dp)
            )
            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                Text(
                    text = playlist.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = pluralStringResource(
                        R.plurals.playlist_track_count,
                        playlist.trackCount,
                        playlist.trackCount
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Draws up to four newest album covers in a reusable two-by-two playlist mosaic. */
@Composable
internal fun PlaylistArtworkMosaic(
    albumArtUris: List<String>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val latestArt = remember(albumArtUris) { albumArtUris.take(MAX_MOSAIC_ALBUMS) }
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center
    ) {
        if (latestArt.isEmpty()) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
        } else {
            latestArt.forEachIndexed { index, artUri ->
                AsyncImage(
                    model = ImageRequest.Builder(context).data(artUri).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize(if (latestArt.size == 1) 1f else 0.5f)
                        .align(mosaicAlignment(index))
                )
            }
        }
    }
}

private const val MAX_MOSAIC_ALBUMS = 4

/** Maps the newest four tracks into stable two-by-two cover positions. */
private fun mosaicAlignment(index: Int): Alignment = when (index) {
    0 -> Alignment.TopStart
    1 -> Alignment.TopEnd
    2 -> Alignment.BottomStart
    else -> Alignment.BottomEnd
}
