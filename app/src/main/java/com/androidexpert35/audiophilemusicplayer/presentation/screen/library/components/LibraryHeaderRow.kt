package com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileMusicPlayerTheme

/**
 * Spotify-style library screen header row.
 *
 * Renders a circular profile avatar on the leading edge, the bold "Library" title
 * occupying the remaining horizontal space, and refresh / add quick-action icons on
 * the trailing edge. This row stays pinned above the scrollable content area so the
 * primary actions are always reachable.
 *
 * The refresh icon spins continuously while [isRefreshing] is `true` to give
 * immediate visual feedback that a re-index is in progress.
 *
 * @param onRefreshClick Invoked when the user taps the refresh icon.
 * @param onAddClick Invoked when the user taps the add (+) icon.
 * @param isRefreshing `true` while the library is being re-indexed; animates the refresh icon.
 * @param modifier Optional [Modifier] for the root row.
 */
@Composable
internal fun LibraryHeaderRow(
    onRefreshClick: () -> Unit,
    onAddClick: () -> Unit,
    isRefreshing: Boolean = false,
    modifier: Modifier = Modifier
) {
    // Infinite rotation used only when a refresh is in progress; the transition is
    // created unconditionally to avoid recomposing the entire row when it starts/stops.
    val infiniteTransition = rememberInfiniteTransition(label = "RefreshIconSpin")
    val rotationDegrees by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = LinearEasing)
        ),
        label = "RefreshIconRotation"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Circular profile avatar placeholder
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.MusicNote,
                contentDescription = stringResource(R.string.cd_library_profile_icon),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(20.dp)
            )
        }

        // Bold "Library" title — fills the remaining horizontal space
        Text(
            text = stringResource(R.string.library_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )

        // Refresh quick-action — spins while a re-index is in progress
        IconButton(
            onClick = onRefreshClick,
            enabled = !isRefreshing
        ) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = stringResource(R.string.cd_refresh_library),
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.rotate(if (isRefreshing) rotationDegrees else 0f)
            )
        }

        // Add (+) quick-action (reserved for future playlist creation)
        IconButton(onClick = onAddClick) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = stringResource(R.string.cd_add_to_library),
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF10141D, name = "LibraryHeaderRow – idle")
@Composable
private fun LibraryHeaderRowIdlePreview() {
    AudiophileMusicPlayerTheme {
        LibraryHeaderRow(
            onRefreshClick = {},
            onAddClick = {},
            isRefreshing = false
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF10141D, name = "LibraryHeaderRow – refreshing")
@Composable
private fun LibraryHeaderRowRefreshingPreview() {
    AudiophileMusicPlayerTheme {
        LibraryHeaderRow(
            onRefreshClick = {},
            onAddClick = {},
            isRefreshing = true
        )
    }
}
