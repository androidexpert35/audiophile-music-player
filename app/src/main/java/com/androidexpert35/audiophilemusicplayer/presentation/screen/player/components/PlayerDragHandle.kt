package com.androidexpert35.audiophilemusicplayer.presentation.screen.player.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * Small pill-shaped drag indicator displayed at the top of the player sheet.
 *
 * Signals to the user that the sheet can be swiped down to dismiss.
 * The colour and opacity intentionally match the system bottom-sheet
 * drag handle convention defined in Material Design 3.
 *
 * @param modifier Optional [Modifier] applied to the pill shape.
 */
@Composable
internal fun PlayerDragHandle(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .width(40.dp)
            .height(4.dp)
            .clip(CircleShape)
            .background(
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.50f)
            )
    )
}

