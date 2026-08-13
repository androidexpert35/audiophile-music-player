package com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Artist

/**
 * Renders an artist image or fallback while requesting missing artwork only once per visible item.
 *
 * Both list and grid artist cards use this component so image loading, fallback rendering, and
 * enrichment requests stay visually and behaviorally consistent.
 */
@Composable
internal fun ArtistArtwork(
    artist: Artist,
    shape: Shape,
    onImageRequest: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val imageRequest = remember(artist.imageUrl) {
        ImageRequest.Builder(context)
            .data(artist.imageUrl)
            .crossfade(200)
            .build()
    }

    LaunchedEffect(artist.id, artist.imageUrl) {
        if (artist.imageUrl.isNullOrBlank()) onImageRequest?.invoke()
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.tertiaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Person,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onTertiaryContainer
        )
        if (!artist.imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = imageRequest,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
