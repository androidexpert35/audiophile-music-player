package com.androidexpert35.audiophilemusicplayer.presentation.screen.common.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileGlassHighlight

/**
 * Pill-shaped interactive audio output control displayed on the right side of the
 * bottom navigation section inside the floating composite panel.
 *
 * The control shows two visual elements side by side:
 * - A **play/pause icon** on the left reflecting the current playback status.
 * - Three **animated vertical bars** on the right that oscillate when audio is
 *   actively playing, giving a real-time visual cue of audio output activity.
 *
 * Tapping the pill dispatches a play/pause toggle via [onPlayPauseClick].
 *
 * @param isPlaying Whether the player is currently in the [PlaybackStatus.PLAYING] state.
 * @param onPlayPauseClick Callback invoked when the user taps the pill to toggle playback.
 * @param modifier Optional [Modifier] applied to the pill root.
 */
@Composable
fun AudioOutputControl(
    isPlaying: Boolean,
    onPlayPauseClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f))
            .border(
                width = 1.dp,
                color = AudiophileGlassHighlight,
                shape = RoundedCornerShape(50)
            )
            .clickable(onClick = onPlayPauseClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Transport icon — reflects the current play/pause state.
        Icon(
            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = stringResource(R.string.cd_audio_output_control),
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(15.dp)
        )

        // Animated visualization bars — oscillate when playing, rest when paused.
        AudioVisualizationBars(isPlaying = isPlaying)
    }
}

/**
 * Three animated vertical bars that visualize audio output activity.
 *
 * Each bar uses an independent phase offset within a shared [rememberInfiniteTransition]
 * to create a staggered waveform effect. When [isPlaying] is `false` the target height
 * collapses to the resting value so all bars animate down to their minimum size.
 *
 * @param isPlaying Whether audio is actively playing; drives the bar animation amplitude.
 * @param barCount Number of visualization bars to render. Defaults to 3.
 * @param modifier Optional [Modifier] applied to the bars row.
 */
@Composable
private fun AudioVisualizationBars(
    isPlaying: Boolean,
    barCount: Int = 3,
    modifier: Modifier = Modifier
) {
    // A single infinite transition drives all bars to avoid scheduling multiple
    // separate animators — only the target value changes per bar.
    val infiniteTransition = rememberInfiniteTransition(label = "audio_viz")

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(barCount) { index ->
            // Stagger each bar's peak height and animation period so they don't
            // move in sync, producing a more organic waveform appearance.
            val peakHeight = if (isPlaying) (8f + index * 4f) else 4f
            val animationDuration = 480 + index * 160

            val height by infiniteTransition.animateFloat(
                initialValue = 4f,
                targetValue = peakHeight,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = animationDuration,
                        easing = FastOutSlowInEasing
                    ),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "bar_height_$index"
            )

            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(height.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

