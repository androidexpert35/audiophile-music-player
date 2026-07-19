package com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileMusicPlayerTheme
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophilePrimaryBright
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophilePrimaryContainer
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileTertiaryContainer

/**
 * Pinned "Liked Songs" special item shown at the top of the library content list or grid.
 *
 * Renders a distinct gradient thumbnail with a heart icon and the collection title / track
 * count beside or below it, depending on [isGridView]. The gradient uses the app's own
 * tertiary and primary container colours rather than Spotify's branded palette.
 *
 * @param trackCount Total number of tracks in the local library (shown as the collection size).
 * @param isGridView When `true`, renders the compact square-grid variant; otherwise the
 *   full-width list-row variant is shown.
 * @param onClick Invoked when the item is tapped.
 * @param modifier Optional [Modifier] for the root layout.
 */
@Composable
internal fun LibraryFavoritesItem(
    trackCount: Int,
    isGridView: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (isGridView) {
        LibraryFavoritesGridItem(
            trackCount = trackCount,
            onClick = onClick,
            modifier = modifier
        )
    } else {
        LibraryFavoritesListItem(
            trackCount = trackCount,
            onClick = onClick,
            modifier = modifier
        )
    }
}

/**
 * Full-width list-row variant of the Liked Songs item.
 *
 * @param trackCount Track count shown as the secondary label.
 * @param onClick Invoked when the row is tapped.
 * @param modifier Optional [Modifier] for the root row.
 */
@Composable
private fun LibraryFavoritesListItem(
    trackCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Memoize the Brush — linearGradient + listOf allocate on every recomposition;
    // these colours are theme constants so the key set is stable.
    val gradientBrush = remember { Brush.linearGradient(
        colors = listOf(AudiophileTertiaryContainer, AudiophilePrimaryContainer)
    ) }
    val trackCountLabel = pluralStringResource(R.plurals.library_track_count, trackCount, trackCount)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Gradient thumbnail with heart icon
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(gradientBrush),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Favorite,
                contentDescription = null,
                tint = AudiophilePrimaryBright,
                modifier = Modifier.size(28.dp)
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = stringResource(R.string.library_favorites_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = trackCountLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Compact two-column grid variant of the Liked Songs item.
 *
 * @param trackCount Track count shown as the secondary label.
 * @param onClick Invoked when the cell is tapped.
 * @param modifier Optional [Modifier] for the root column.
 */
@Composable
private fun LibraryFavoritesGridItem(
    trackCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Memoize the Brush — same constant colours as the list variant.
    val gradientBrush = remember { Brush.linearGradient(
        colors = listOf(AudiophileTertiaryContainer, AudiophilePrimaryContainer)
    ) }
    val trackCountLabel = pluralStringResource(R.plurals.library_track_count, trackCount, trackCount)

    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.medium)
                .background(gradientBrush),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Favorite,
                contentDescription = null,
                tint = AudiophilePrimaryBright,
                modifier = Modifier.size(48.dp)
            )
        }

        Text(
            text = stringResource(R.string.library_favorites_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = trackCountLabel,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF10141D, name = "LibraryFavoritesItem – List view")
@Composable
private fun LibraryFavoritesItemListPreview() {
    AudiophileMusicPlayerTheme {
        LibraryFavoritesItem(
            trackCount = 248,
            isGridView = false,
            onClick = {},
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF10141D, name = "LibraryFavoritesItem – Grid view")
@Composable
private fun LibraryFavoritesItemGridPreview() {
    AudiophileMusicPlayerTheme {
        LibraryFavoritesItem(
            trackCount = 248,
            isGridView = true,
            onClick = {},
            modifier = Modifier.padding(8.dp)
        )
    }
}

