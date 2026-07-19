package com.androidexpert35.audiophilemusicplayer.presentation.screen.player.components

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophilePrimary
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileScrim
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileTertiary

/**
 * Full-screen blurred album art background with layered scrim and radial bloom.
 *
 * Provides the signature "frosted glass" look: the current album art is
 * rendered at full bleed, heavily blurred, then dimmed with a five-stop
 * vertical gradient and a subtle radial bloom of the primary accent colour
 * centred on the artwork zone. This creates an immersive, warm atmosphere
 * that lets foreground controls remain legible.
 *
 * @param albumId MediaStore album identifier used to resolve the album art URI.
 * @param modifier Optional [Modifier] applied to the root container.
 */
@Composable
internal fun BlurredBackground(
    albumId: Long,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val artUri: Uri = remember(albumId) {
        ContentUris.withAppendedId(
            MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
            albumId
        )
    }

    val imageRequest = remember(albumId) {
        ImageRequest.Builder(context)
            .data(artUri)
            .crossfade(300)
            .build()
    }

    val scrimBrush = remember {
        Brush.verticalGradient(
            colorStops = arrayOf(
                0.00f to AudiophileScrim.copy(alpha = 0.40f),
                0.25f to AudiophileScrim.copy(alpha = 0.52f),
                0.50f to AudiophileScrim.copy(alpha = 0.65f),
                0.75f to AudiophileScrim.copy(alpha = 0.82f),
                1.00f to Color.Black.copy(alpha = 0.94f)
            )
        )
    }

    val bloomBrush = remember {
        Brush.radialGradient(
            colorStops = arrayOf(
                0.0f to AudiophilePrimary.copy(alpha = 0.06f),
                0.35f to AudiophileTertiary.copy(alpha = 0.03f),
                1.0f to Color.Transparent
            ),
            center = Offset.Unspecified,
            radius = 900f
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
            }
    ) {
        // Heavily blurred album art layer — increased radius for a smoother wash
        AsyncImage(
            model = imageRequest,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .blur(72.dp)
        )

        // Five-stop vertical scrim — lighter at top for artwork visibility,
        // heavier at bottom for control-panel legibility
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(scrimBrush)
        )

        // Radial bloom — warm primary/tertiary glow centred in the artwork zone
        // to give depth and colour resonance behind the hero artwork
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(bloomBrush)
        )
    }
}

