package com.androidexpert35.audiophilemusicplayer.presentation.screen.common.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Default minimum interval between accepted clicks for transport controls.
 *
 * 300 ms matches a fast-but-intentional double-tap cadence while still rejecting
 * accidental rapid bursts that can confuse the playback engine.
 */
private const val DEFAULT_THROTTLE_INTERVAL_MS = 300L

/**
 * Returns a click handler that drops invocations arriving faster than [intervalMs].
 *
 * This is a defence-in-depth measure for playback transport controls: the
 * [PlaybackController][com.androidexpert35.audiophilemusicplayer.data.playback.PlaybackController]
 * already serializes commands with a `Mutex`, but throttling at the UI layer
 * prevents unnecessary coroutine launches and keeps animations smooth.
 *
 * Usage:
 * ```kotlin
 * val throttledPause = rememberThrottledClick { onEvent(PlayerUiEvent.Pause) }
 * IconButton(onClick = throttledPause) { … }
 * ```
 *
 * @param intervalMs Minimum milliseconds between accepted clicks.
 * @param onClick Action to perform when a click is accepted.
 * @return A stable lambda suitable for `onClick` parameters.
 */
@Composable
fun rememberThrottledClick(
    intervalMs: Long = DEFAULT_THROTTLE_INTERVAL_MS,
    onClick: () -> Unit
): () -> Unit {
    var lastClickTime by remember { mutableLongStateOf(0L) }

    return remember(onClick, intervalMs) {
        {
            val now = System.currentTimeMillis()
            if (now - lastClickTime >= intervalMs) {
                lastClickTime = now
                onClick()
            }
        }
    }
}

