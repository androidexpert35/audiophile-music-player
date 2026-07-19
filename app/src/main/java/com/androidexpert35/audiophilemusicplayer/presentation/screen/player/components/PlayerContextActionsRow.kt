package com.androidexpert35.audiophilemusicplayer.presentation.screen.player.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileGlassHighlight
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileMusicPlayerTheme

/**
 * Secondary player actions rendered as glassmorphism-styled chips.
 *
 * Uses the same semi-transparent surface + glass highlight border treatment
 * as the main control panel for visual consistency across the player screen.
 *
 * @param isQueueEnabled Whether the queue action should be enabled.
 * @param isLyricsEnabled Whether the lyrics action should be enabled.
 * @param isDestinationEnabled Whether the contextual navigation action should be enabled.
 * @param onQueueClick Callback opening the playback queue.
 * @param onLyricsClick Callback opening the lyrics sheet.
 * @param onDestinationClick Callback opening the album/artist destination chooser.
 * @param modifier Optional [Modifier] for the root container.
 */
@Composable
internal fun PlayerContextActionsRow(
    modifier: Modifier = Modifier,
    isQueueEnabled: Boolean,
    isLyricsEnabled: Boolean,
    isDestinationEnabled: Boolean,
    onQueueClick: () -> Unit,
    onLyricsClick: () -> Unit,
    onDestinationClick: () -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        GlassActionChip(
            icon = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                    contentDescription = stringResource(R.string.cd_open_playback_queue),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            },
            label = stringResource(R.string.player_queue_action),
            enabled = isQueueEnabled,
            onClick = onQueueClick
        )
        GlassActionChip(
            icon = {
                Icon(
                    imageVector = Icons.Filled.MusicNote,
                    contentDescription = stringResource(R.string.cd_open_lyrics),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            },
            label = stringResource(R.string.player_lyrics_action),
            enabled = isLyricsEnabled,
            onClick = onLyricsClick
        )
        GlassActionChip(
            icon = {
                Icon(
                    imageVector = Icons.Filled.Explore,
                    contentDescription = stringResource(R.string.cd_open_track_destinations),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            },
            label = stringResource(R.string.player_go_to_action),
            enabled = isDestinationEnabled,
            onClick = onDestinationClick
        )
    }
}

/**
 * Glassmorphism-styled action chip matching the player control panel aesthetic.
 *
 * @param icon Leading icon composable.
 * @param label Text label displayed next to the icon.
 * @param enabled Whether the chip is interactive.
 * @param onClick Callback when the chip is tapped.
 */
@Composable
private fun GlassActionChip(
    icon: @Composable () -> Unit,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val shape = MaterialTheme.shapes.large
    val contentAlpha = if (enabled) 1f else 0.45f

    Surface(
        modifier = Modifier
            .clip(shape)
            .border(
                width = 1.dp,
                color = AudiophileGlassHighlight,
                shape = shape
            )
            .then(
                if (enabled) Modifier.clickable(onClick = onClick)
                else Modifier
            ),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.50f),
        shape = shape,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .alpha(contentAlpha),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(contentAlignment = Alignment.Center) {
                icon()
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF101114)
@Composable
private fun PlayerContextActionsRowPreview() {
    AudiophileMusicPlayerTheme {
        PlayerContextActionsRow(
            isQueueEnabled = true,
            isLyricsEnabled = true,
            isDestinationEnabled = true,
            onQueueClick = {},
            onLyricsClick = {},
            onDestinationClick = {}
        )
    }
}

