package com.androidexpert35.audiophilemusicplayer.presentation.screen.player.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.player.PlayerUiEvent
import kotlinx.coroutines.delay

/**
 * Seek bar with elapsed / remaining time labels placed above the M3 [Slider].
 *
 * While the user is dragging, the slider shows the local drag value;
 * once released, it emits a [PlayerUiEvent.SeekTo] with the target
 * position. This prevents the slider from "jumping" on every state update.
 *
 * Both [positionMs] and [durationMs] are lambdas so that the caller can pass
 * references to a Compose `State` object read without subscribing the parent
 * composable. Position ticks therefore recompose only this leaf, not the
 * surrounding player layout.
 *
 * Time labels sit above the track for a cleaner, less bottom-heavy look.
 *
 * @param positionMs Lambda returning the current playback position in milliseconds.
 * @param durationMs Lambda returning the total track duration in milliseconds.
 * @param onEvent Callback to emit [PlayerUiEvent]s (specifically [PlayerUiEvent.SeekTo]).
 * @param modifier Optional [Modifier] for the root container.
 */
@Composable
internal fun SeekBar(
    positionMs: () -> Long,
    durationMs: () -> Long,
    onEvent: (PlayerUiEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    // Local drag state: NaN when not dragging, a fraction while the thumb is held
    var isDragging by remember { mutableFloatStateOf(Float.NaN) }
    var pendingSeekPositionMs by remember {
        mutableLongStateOf(SeekBarStateResolver.NoPendingSeekPositionMs)
    }
    var pendingSeekDurationMs by remember { mutableLongStateOf(0L) }

    val externalPositionMs = positionMs().coerceAtLeast(0L)
    val externalDurationMs = durationMs().coerceAtLeast(0L)

    LaunchedEffect(
        externalPositionMs,
        externalDurationMs,
        pendingSeekPositionMs,
        pendingSeekDurationMs,
    ) {
        if (
            SeekBarStateResolver.shouldClearPendingSeek(
                externalPositionMs = externalPositionMs,
                externalDurationMs = externalDurationMs,
                pendingSeekPositionMs = pendingSeekPositionMs,
                pendingSeekDurationMs = pendingSeekDurationMs,
            )
        ) {
            pendingSeekPositionMs = SeekBarStateResolver.NoPendingSeekPositionMs
            pendingSeekDurationMs = 0L
        }
    }

    LaunchedEffect(pendingSeekPositionMs) {
        if (pendingSeekPositionMs == SeekBarStateResolver.NoPendingSeekPositionMs) return@LaunchedEffect

        // Avoid pinning the thumb forever if the backend never acknowledges the seek.
        delay(PENDING_SEEK_TIMEOUT_MS)
        if (pendingSeekPositionMs != SeekBarStateResolver.NoPendingSeekPositionMs) {
            pendingSeekPositionMs = SeekBarStateResolver.NoPendingSeekPositionMs
            pendingSeekDurationMs = 0L
        }
    }

    // Capture the token before entering the remember lambda — MaterialTheme.colorScheme
    // is @Composable and cannot be accessed inside a non-composable lambda.
    val outlineColor = MaterialTheme.colorScheme.outline
    // Pre-compute the inactive track color once per theme change so that copy(alpha)
    // does not allocate a new Color object on every position-tick recomposition.
    val inactiveTrackColor = remember(outlineColor) { outlineColor.copy(alpha = 0.35f) }

    val displayedPositionMs = SeekBarStateResolver.resolveDisplayedPositionMs(
        externalPositionMs = externalPositionMs,
        externalDurationMs = externalDurationMs,
        dragFraction = isDragging,
        pendingSeekPositionMs = pendingSeekPositionMs,
        pendingSeekDurationMs = pendingSeekDurationMs,
    )

    val sliderValue = if (!isDragging.isNaN() && externalDurationMs > 0L) {
        isDragging.coerceIn(0f, 1f)
    } else if (externalDurationMs > 0L) {
        displayedPositionMs.toFloat() / externalDurationMs.toFloat()
    } else {
        0f
    }

    val elapsedText = formatDuration(displayedPositionMs)
    val totalText = formatDuration(externalDurationMs)

    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        // Time labels above the slider for a cleaner visual hierarchy
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = elapsedText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = totalText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Slider(
            value = sliderValue,
            onValueChange = { isDragging = it },
            onValueChangeFinished = {
                if (!isDragging.isNaN()) {
                    val seekTarget = (isDragging * externalDurationMs).toLong()
                    pendingSeekPositionMs = seekTarget
                    pendingSeekDurationMs = externalDurationMs
                    onEvent(PlayerUiEvent.SeekTo(seekTarget))
                    isDragging = Float.NaN
                }
            },
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = inactiveTrackColor
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Converts milliseconds to a `mm:ss` formatted string.
 *
 * @param ms Duration in milliseconds.
 * @return Formatted time string (e.g., "03:25").
 */
private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

private const val PENDING_SEEK_TIMEOUT_MS = 2_000L

