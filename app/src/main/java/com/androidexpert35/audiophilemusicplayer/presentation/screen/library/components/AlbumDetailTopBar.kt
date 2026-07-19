package com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidexpert35.audiophilemusicplayer.R

/**
 * Floating transparent top bar for the album detail screen.
 *
 * Provides a back button that is always tappable regardless of scroll position,
 * and an album title that fades in as the user scrolls the hero image off screen.
 *
 * ## Draw-phase alpha optimisation
 * [topBarAlphaProvider] is a lambda that is **only called inside draw-phase blocks**
 * (`drawBehind`, `graphicsLayer`). Compose invalidates only those draw layers when the
 * returned value changes — this composable itself never recomposes on scroll, keeping
 * the frame cost of scrolling minimal.
 *
 * @param albumTitle Title shown once the hero artwork scrolls off screen.
 * @param topBarAlphaProvider Lambda returning the current top-bar alpha `[0, 1]`.
 *   Read exclusively in draw-phase lambdas so the composable is scroll-recompose-free.
 * @param onNavigateBack Callback invoked when the back button is tapped.
 * @param modifier Optional [Modifier] for the root [Row].
 */
@Composable
fun AlbumDetailTopBar(
    albumTitle: String,
    topBarAlphaProvider: () -> Float,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Capture theme colors once in the composition phase so the draw-phase lambdas
    // below read stable Color values, not composition-locals, on every draw call.
    val backgroundColor = MaterialTheme.colorScheme.background
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface

    // Stable lambda for the pill alpha — inverse of the bar alpha.
    // Captured once here so the graphicsLayer lambda below is a stable function literal.
    val pillAlphaProvider: () -> Float = remember(topBarAlphaProvider) {
        { (1f - topBarAlphaProvider()).coerceAtLeast(0f) }
    }

    Row(
        modifier = modifier
            .statusBarsPadding()
            .fillMaxWidth()
            .height(56.dp)
            // drawBehind runs in the draw phase; reading topBarAlphaProvider() here
            // invalidates only this draw layer on scroll — no recomposition needed.
            .drawBehind { drawRect(color = backgroundColor, alpha = topBarAlphaProvider() * 0.94f) }
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 40 dp touch-target Box stacks the fading pill background and the always-opaque icon.
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(40.dp)
        ) {
            // Pill background fades out as the solid bar background fades in.
            // graphicsLayer applies the alpha change in the draw phase only.
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .graphicsLayer { alpha = pillAlphaProvider() }
                    .background(color = surfaceColor.copy(alpha = 0.72f), shape = CircleShape)
            )
            // Transparent Surface carries the ripple and accessibility semantics;
            // the icon itself is always fully opaque at all scroll positions.
            Surface(
                onClick = onNavigateBack,
                shape = CircleShape,
                color = Color.Transparent,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.cd_navigate_back),
                        tint = onSurfaceColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // Album title fades in as the bar becomes opaque. graphicsLayer keeps the
        // alpha change in the draw phase without recomposing the Text.
        Text(
            text = albumTitle,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = onSurfaceColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp)
                .graphicsLayer { alpha = topBarAlphaProvider() }
        )
    }
}

