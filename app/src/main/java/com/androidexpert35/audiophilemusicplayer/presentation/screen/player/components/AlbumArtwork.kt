package com.androidexpert35.audiophilemusicplayer.presentation.screen.player.components

import android.content.ContentUris
import android.provider.MediaStore
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import coil3.compose.SubcomposeAsyncImageContent
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophilePrimary
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileSecondary
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileTertiary

/**
 * Displays the current track's album art as a large, clean rounded-corner square.
 *
 * **Loading strategy**
 * 1. If [localArtUri] is provided and non-blank (e.g. a `file://` URI for DSD tracks
 *    whose artwork was extracted from an embedded APIC tag at scan time), it is used
 *    as the primary source.
 * 2. Otherwise constructs the local MediaStore content URI from [albumId]
 *    (`content://media/external/audio/albums/<albumId>`).
 * 3. If the primary source fails, falls back to [remoteArtUrl] when provided —
 *    this is the Deezer cover URL cached by the remote image repository.
 * 4. If both sources fail or are `null`, renders [ArtworkPlaceholder].
 *
 * @param albumId MediaStore album identifier; used for the MediaStore artwork URI
 *   when [localArtUri] is absent.
 * @param albumTitle Album title used for the accessibility content description.
 * @param localArtUri Optional explicit artwork URI. Takes priority over the MediaStore
 *   URI when non-null; should be set for DSD tracks and any track whose art URI was
 *   pre-computed at scan time.
 * @param remoteArtUrl Optional Deezer HTTPS cover URL used as a network fallback
 *   when the local source yields no image. Pass `null` to skip the network step.
 * @param onClick Optional callback invoked when the artwork is tapped.
 * @param modifier Optional [Modifier] applied to the outer container.
 */
@Composable
internal fun AlbumArtwork(
    albumId: Long,
    albumTitle: String,
    localArtUri: String? = null,
    remoteArtUrl: String? = null,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Prefer the pre-computed URI (works for both DSD file:// and regular content://).
    // Fall back to the MediaStore album-art construction only when no explicit URI exists.
    val primaryArtData: Any = remember(localArtUri, albumId) {
        if (!localArtUri.isNullOrBlank()) {
            localArtUri
        } else {
            ContentUris.withAppendedId(
                MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
                albumId
            )
        }
    }

    val artworkShape = RoundedCornerShape(28.dp)

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        // Inner sizing box preserves the cover's square viewport without adding a halo.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            contentAlignment = Alignment.Center
        ) {
            Card(
                onClick = onClick ?: {},
                enabled = onClick != null,
                modifier = Modifier.fillMaxSize(),
                shape = artworkShape,
                colors = CardDefaults.cardColors(
                    containerColor = Color.Transparent
                )
            ) {
                // Phase 1: try the pre-computed local art URI (covers DSD file:// and MediaStore content://)
                SubcomposeAsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(primaryArtData)
                        .crossfade(300)
                        .build(),
                    contentDescription = stringResource(R.string.cd_album_art, albumTitle),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(artworkShape),
                    loading = {
                        ArtworkPlaceholder()
                    },
                    success = {
                        SubcomposeAsyncImageContent()
                    },
                    error = {
                        // Phase 2: local URI returned no artwork — try Deezer remote URL
                        if (!remoteArtUrl.isNullOrBlank()) {
                            SubcomposeAsyncImage(
                                model = ImageRequest.Builder(context)
                                    .data(remoteArtUrl)
                                    .crossfade(300)
                                    .build(),
                                contentDescription = stringResource(R.string.cd_album_art, albumTitle),
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(artworkShape),
                                error = { ArtworkPlaceholder() },
                                success = { SubcomposeAsyncImageContent() }
                            )
                        } else {
                            // Phase 3: no remote URL available — render the styled placeholder
                            ArtworkPlaceholder()
                        }
                    }
                )
            }
        }
    }
}

/**
 * Stylish placeholder shown when album art is unavailable.
 *
 * Combines a rich diagonal gradient background, concentric arc decorations
 * inspired by a vinyl groove pattern, and a centred music-note icon to
 * create a polished "no artwork" state that blends with the audiophile
 * dark theme.
 */
@Composable
private fun ArtworkPlaceholder() {
    val primaryColor = AudiophilePrimary
    val secondaryColor = AudiophileSecondary
    val tertiaryColor = AudiophileTertiary

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colorStops = arrayOf(
                        0.0f to Color(0xFF141826),
                        0.35f to Color(0xFF1A2238),
                        0.65f to Color(0xFF22263E),
                        1.0f to Color(0xFF2A2144)
                    ),
                    start = Offset.Zero,
                    end = Offset.Infinite
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        // Decorative concentric arcs — vinyl groove / sound-wave motif
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val maxRadius = size.minDimension / 2f

            // Draw concentric rings expanding outward from the centre
            val ringCount = 5
            for (i in 1..ringCount) {
                val fraction = i.toFloat() / ringCount
                val radius = maxRadius * fraction * 0.85f
                // Alternate between primary/secondary/tertiary tints
                val ringColor = when (i % 3) {
                    0 -> primaryColor
                    1 -> secondaryColor
                    else -> tertiaryColor
                }
                drawCircle(
                    color = ringColor.copy(alpha = 0.06f + (fraction * 0.04f)),
                    radius = radius,
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.5f)
                )
            }

            // Subtle radial glow at the centre
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.0f to primaryColor.copy(alpha = 0.12f),
                        0.5f to tertiaryColor.copy(alpha = 0.06f),
                        1.0f to Color.Transparent
                    ),
                    center = Offset(cx, cy),
                    radius = maxRadius * 0.55f
                ),
                radius = maxRadius * 0.55f,
                center = Offset(cx, cy)
            )
        }

        // Music-note icon — large and softly glowing
        Icon(
            imageVector = Icons.Rounded.MusicNote,
            contentDescription = null,
            tint = primaryColor.copy(alpha = 0.55f),
            modifier = Modifier.size(72.dp)
        )
    }
}
