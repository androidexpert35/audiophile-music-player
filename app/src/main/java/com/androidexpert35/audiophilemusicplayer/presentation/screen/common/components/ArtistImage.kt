package com.androidexpert35.audiophilemusicplayer.presentation.screen.common.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileMusicPlayerTheme

/**
 * Circular artist portrait that loads a remote Deezer image URL via Coil with a
 * smooth crossfade transition and a Material Design 3 fallback for offline / no-result cases.
 *
 * **Loading strategy**
 * - When [imageUrl] is non-null and non-blank, Coil fetches it asynchronously using
 *   the OkHttp-backed singleton [ImageLoader][coil3.ImageLoader] configured in
 *   [com.androidexpert35.audiophilemusicplayer.di.CoilModule].
 * - While loading, a skeleton placeholder is shown.
 * - If the network request fails or [imageUrl] is `null`, a styled circular placeholder
 *   featuring a person icon is rendered. This keeps the layout stable regardless of
 *   connectivity or Deezer availability.
 *
 * @param imageUrl HTTPS URL of the artist image resolved from Deezer, or `null` when
 *   the artist has not been enriched yet or Deezer returned no result.
 * @param artistName Display name used for the content description (accessibility).
 * @param size Diameter of the circular portrait. Defaults to [ArtistImageDefaults.Size].
 * @param modifier Optional [Modifier] applied to the outer container.
 */
@Composable
fun ArtistImage(
    imageUrl: String?,
    artistName: String,
    size: Dp = ArtistImageDefaults.Size,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (!imageUrl.isNullOrBlank()) {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(context)
                    .data(imageUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = artistName,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape),
                loading = {
                    // Retain the placeholder background while the image loads so the
                    // layout does not jump; the crossfade handles the visual transition.
                    ArtistImagePlaceholder(size = size)
                },
                error = {
                    ArtistImagePlaceholder(size = size)
                },
                success = {
                    SubcomposeAsyncImageContent()
                }
            )
        } else {
            ArtistImagePlaceholder(size = size)
        }
    }
}

/**
 * Material Design 3 fallback composable shown when no artist image is available.
 *
 * Renders a tinted person icon centred on the [MaterialTheme.colorScheme.surfaceVariant]
 * background, providing a visually consistent placeholder that integrates naturally
 * with the app's colour scheme.
 *
 * @param size Diameter of the circular area, used to scale the icon proportionally.
 */
@Composable
private fun ArtistImagePlaceholder(size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .background(MaterialTheme.colorScheme.surfaceVariant, shape = CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.Person,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.60f),
            modifier = Modifier.size(size * 0.55f)
        )
    }
}

/**
 * Default dimension constants for [ArtistImage].
 */
object ArtistImageDefaults {
    /** Default diameter for the artist portrait circle. */
    val Size: Dp = 56.dp
}

@Preview(name = "ArtistImage – with URL (simulated as null for preview)", showBackground = true)
@Composable
private fun ArtistImagePreview() {
    AudiophileMusicPlayerTheme {
        ArtistImage(
            imageUrl = null,
            artistName = "Miles Davis",
            size = 80.dp
        )
    }
}

@Preview(name = "ArtistImage – large placeholder", showBackground = true)
@Composable
private fun ArtistImageLargePreview() {
    AudiophileMusicPlayerTheme {
        ArtistImage(
            imageUrl = null,
            artistName = "John Coltrane",
            size = 120.dp
        )
    }
}

