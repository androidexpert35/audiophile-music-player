package com.androidexpert35.audiophilemusicplayer.presentation.screen.player.components

import android.content.ContentUris
import android.provider.MediaStore
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileTertiary

/**
 * Compact square album-art tile with a layered liquid-glass / glassmorphism finish.
 *
 * The effect is built from three superimposed layers rendered in Z-order:
 * 1. **Album art image** — Coil-loaded artwork clipped to a rounded square, with
 *    a remote-URL fallback and a styled placeholder when no art is available.
 * 2. **Glass overlay** — a Canvas layer drawn *inside* the clip that adds a
 *    frosted-glass tint, a bright top specular highlight, and a left-edge
 *    secondary refraction gradient.
 * 3. **Glass rim** — a Canvas layer drawn *outside* the clip that renders a
 *    semi-transparent white stroke around the tile plus a top-left specular
 *    arc that mimics liquid-glass rim lighting.
 *
 * **Loading strategy**
 * 1. [localArtUri] (non-blank) is used as the primary source.
 * 2. Falls back to the MediaStore album-art URI derived from [albumId].
 * 3. If that fails and [remoteArtUrl] is provided, the remote URL is attempted.
 * 4. If all sources fail, [LyricsSheetMiniArtPlaceholder] is rendered.
 *
 * @param albumId MediaStore album identifier used to construct the fallback URI.
 * @param albumTitle Album title used for the placeholder icon content description.
 * @param localArtUri Optional pre-computed artwork URI; takes priority over the
 *   MediaStore URI when non-null and non-blank.
 * @param remoteArtUrl Optional HTTPS cover URL used as a last-resort network
 *   fallback. Pass `null` to skip the network step.
 * @param modifier Modifier applied to the outermost container Box.
 */
@Composable
internal fun LyricsSheetMiniArt(
    albumId: Long,
    albumTitle: String,
    modifier: Modifier = Modifier,
    localArtUri: String? = null,
    remoteArtUrl: String? = null,
) {
    val context = LocalContext.current
    val cornerRadius = 12.dp
    val shape = RoundedCornerShape(cornerRadius)

    // Prefer an explicit pre-computed URI (DSD, embedded art) over the
    // generic MediaStore album-art construction.
    val primaryArtData: Any = remember(localArtUri, albumId) {
        if (!localArtUri.isNullOrBlank()) {
            localArtUri
        } else {
            ContentUris.withAppendedId(
                MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
                albumId,
            )
        }
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {


        // ── Layer 1 + 2: Clipped art + frosted overlay ───────────────────────
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape),
        ) {
            // Album art image (Coil with two-stage fallback)
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(context)
                    .data(primaryArtData)
                    .crossfade(300)
                    .build(),
                contentDescription = albumTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = { LyricsSheetMiniArtPlaceholder() },
                success = { SubcomposeAsyncImageContent() },
                error = {
                    if (!remoteArtUrl.isNullOrBlank()) {
                        SubcomposeAsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(remoteArtUrl)
                                .crossfade(300)
                                .build(),
                            contentDescription = albumTitle,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                            success = { SubcomposeAsyncImageContent() },
                            error = { LyricsSheetMiniArtPlaceholder() },
                        )
                    } else {
                        LyricsSheetMiniArtPlaceholder()
                    }
                },
            )

            // ── Layer 2: Liquid-glass tint + specular gradients ─────────────
            // Drawn inside the clip so it respects the rounded corners.
            // The three fill passes simulate:
            //  a) a universal frosted-glass tint
            //  b) a top-edge catch-light (light source above)
            //  c) a left-edge refraction (secondary internal reflection)
            Canvas(modifier = Modifier.fillMaxSize()) {
                // (a) Base frosted tint — keeps the glass analogy consistent even
                //     when the underlying artwork is very bright.
                drawRect(color = Color.White.copy(alpha = 0.05f))

                // (b) Top specular: bright at the very top, fading to nothing at
                //     ~55% down so the artwork still reads clearly.
                drawRect(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to Color.White.copy(alpha = 0.35f),
                            0.28f to Color.White.copy(alpha = 0.12f),
                            0.55f to Color.Transparent,
                        ),
                        endY = size.height,
                    ),
                )

                // (c) Left-edge secondary refraction — subtler than the top highlight
                //     to mimic the curved inner surface of a liquid lens.
                drawRect(
                    brush = Brush.horizontalGradient(
                        colorStops = arrayOf(
                            0.00f to Color.White.copy(alpha = 0.16f),
                            0.35f to Color.Transparent,
                        ),
                        endX = size.width,
                    ),
                )
            }
        }

        // ── Layer 3: Glass rim ───────────────────────────────────────────────
        // Drawn outside the clipped Box so the stroke is crisp and sits fully
        // on top of all content. Two passes:
        //  (i)  a uniform semi-transparent border for the overall glass edge
        //  (ii) a gradient-tinted arc brightest at the top-left to evoke the
        //       intense rim light of a liquid-glass surface.
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cr = cornerRadius.toPx()
            val strokePx = 1.dp.toPx()
            val halfStroke = strokePx / 2f

            // Inset the rect by half the stroke width so it stays inside bounds.
            val insetTopLeft = Offset(halfStroke, halfStroke)
            val insetSize = Size(
                width = size.width - strokePx,
                height = size.height - strokePx,
            )
            val insetCorner = CornerRadius(cr - halfStroke)

            // (i) Uniform glass edge
            drawRoundRect(
                color = Color.White.copy(alpha = 0.28f),
                topLeft = insetTopLeft,
                size = insetSize,
                cornerRadius = insetCorner,
                style = Stroke(width = strokePx),
            )

            // (ii) Top-left specular arc — bright white fading to transparent
            //      along the diagonal, replicating liquid-glass rim lighting.
            drawRoundRect(
                brush = Brush.linearGradient(
                    colorStops = arrayOf(
                        0.00f to Color.White.copy(alpha = 0.70f),
                        0.40f to Color.White.copy(alpha = 0.20f),
                        0.65f to Color.Transparent,
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(size.width * 0.75f, size.height * 0.55f),
                ),
                topLeft = insetTopLeft,
                size = insetSize,
                cornerRadius = insetCorner,
                style = Stroke(width = strokePx),
            )
        }
    }
}

/**
 * Minimal fallback rendered inside [LyricsSheetMiniArt] when no artwork is
 * available from any source.
 *
 * Uses a dark diagonal gradient background with a softly tinted music-note
 * icon centred inside the tile so the header remains visually coherent even
 * without artwork.
 */
@Composable
private fun LyricsSheetMiniArtPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colorStops = arrayOf(
                        0.00f to Color(0xFF141826),
                        0.50f to Color(0xFF1A2238),
                        1.00f to Color(0xFF22263E),
                    ),
                    start = Offset.Zero,
                    end = Offset.Infinite,
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.MusicNote,
            contentDescription = null,
            tint = AudiophileTertiary.copy(alpha = 0.45f),
            modifier = Modifier.fillMaxSize(0.45f),
        )
    }
}

