package com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.androidexpert35.audiophilemusicplayer.domain.model.library.PlaylistKind
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophilePrimaryBright
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophilePrimaryContainer
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileTertiaryContainer

/**
 * Renders playlist artwork while preserving the favorites collection's heart identity.
 *
 * @param kind Semantic playlist role selecting the system heart or standard cover mosaic.
 * @param albumArtUris Artwork for up to four recent playlist entries.
 * @param modifier Modifier defining the artwork bounds.
 */
@Composable
internal fun PlaylistArtwork(
    kind: PlaylistKind,
    albumArtUris: List<String>,
    modifier: Modifier = Modifier
) {
    if (kind == PlaylistKind.FAVORITES) {
        FavoritesPlaylistArtwork(modifier)
    } else {
        PlaylistArtworkMosaic(albumArtUris, modifier)
    }
}

/** Draws the reserved favorites gradient and heart at every playlist preview size. */
@Composable
private fun FavoritesPlaylistArtwork(modifier: Modifier) {
    val gradient = remember {
        Brush.linearGradient(
            colors = listOf(AudiophileTertiaryContainer, AudiophilePrimaryContainer)
        )
    }
    Box(
        modifier = modifier.clip(MaterialTheme.shapes.medium).background(gradient),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Favorite,
            contentDescription = null,
            tint = AudiophilePrimaryBright,
            modifier = Modifier.fillMaxSize(0.48f)
        )
    }
}

/** Draws up to four newest album covers in a reusable two-by-two playlist mosaic. */
@Composable
private fun PlaylistArtworkMosaic(
    albumArtUris: List<String>,
    modifier: Modifier
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
