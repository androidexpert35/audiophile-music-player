package com.androidexpert35.audiophilemusicplayer.presentation.screen.player.components.telemetrydialog

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileGlassHighlight
import com.androidexpert35.audiophilemusicplayer.presentation.theme.MotionTokens

/**
 * Compact expandable glass row for secondary telemetry details.
 *
 * Keeps the first read focused while still allowing advanced source, routing, or
 * output data to remain discoverable in-place.
 *
 * @param title Header text describing the hidden detail group.
 * @param modifier Optional [Modifier] applied to the outer surface.
 * @param initiallyExpanded Whether the detail group should be open on first render.
 * @param accentColor Tint for the disclosure icon and border highlight.
 * @param content Expanded row content.
 */
@Composable
internal fun ExpandableTelemetryRow(
    title: String,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
    accentColor: Color,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color.White.copy(alpha = 0.055f),
        tonalElevation = 0.dp,
        border = BorderStroke(1.dp, AudiophileGlassHighlight),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.88f),
                    fontWeight = FontWeight.SemiBold,
                )
                Icon(
                    imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null,
                    tint = accentColor.copy(alpha = 0.86f),
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(animationSpec = androidx.compose.animation.core.tween(MotionTokens.DurationShort)) +
                    expandVertically(animationSpec = androidx.compose.animation.core.tween(MotionTokens.DurationShort)),
                exit = fadeOut(animationSpec = androidx.compose.animation.core.tween(MotionTokens.DurationShort)) +
                    shrinkVertically(animationSpec = androidx.compose.animation.core.tween(MotionTokens.DurationShort)),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    content()
                }
            }
        }
    }
}

