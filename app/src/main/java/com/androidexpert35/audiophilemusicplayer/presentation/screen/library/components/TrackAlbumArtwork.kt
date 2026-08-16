package com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components

import android.content.ContentUris
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track
import com.androidexpert35.audiophilemusicplayer.presentation.theme.MotionTokens

/**
 * Renders a track thumbnail from its album artwork, with a music-note fallback.
 *
 * Prefers artwork embedded or cached in [Track.artUri], then uses the corresponding
 * MediaStore album artwork when available. The supplied [shape] clips both sources
 * consistently in compact list rows and larger artwork cards.
 *
 * @param track Track whose album artwork is shown.
 * @param shape Shape applied to the thumbnail and its artwork.
 * @param modifier Optional [Modifier] that determines the thumbnail size.
 * @param fallbackIconSize Size of the music-note fallback icon.
 */
@Composable
internal fun TrackAlbumArtwork(
    track: Track,
    shape: Shape,
    modifier: Modifier = Modifier,
    fallbackIconSize: Dp = 48.dp,
) {
    val context = LocalContext.current
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

    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.55f),
            modifier = Modifier.size(fallbackIconSize)
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
    }
}
