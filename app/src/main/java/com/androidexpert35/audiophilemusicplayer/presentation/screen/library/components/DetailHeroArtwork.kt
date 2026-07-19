package com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp

/**
 * Provides the shared elevated square artwork treatment for album and playlist heroes.
 *
 * @param modifier Optional modifier applied before the standard artwork sizing and elevation.
 * @param content Artwork or a purpose-built placeholder rendered inside the square frame.
 */
@Composable
internal fun DetailHeroArtwork(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val artworkShape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp)
            .shadow(
                elevation = 24.dp,
                shape = artworkShape,
                ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
            )
            .clip(artworkShape)
            .aspectRatio(1f)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
        content = content
    )
}
