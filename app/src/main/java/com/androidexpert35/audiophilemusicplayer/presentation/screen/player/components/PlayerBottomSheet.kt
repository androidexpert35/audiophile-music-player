package com.androidexpert35.audiophilemusicplayer.presentation.screen.player.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Full-screen container that supports swipe-down dismissal for the now-playing overlay.
 *
 * The drag offsets are stored in plain [mutableFloatStateOf]. Their writes in
 * [rememberDraggableState] is synchronous — no coroutine, no mutex, no queue — so the
 * sheet tracks the finger exactly every frame.
 *
 * **Off-screen entry reset**: When [isOpen] transitions from `false` to `true`, a
 * [LaunchedEffect] snaps both offsets back to `0f`. The outer `graphicsLayer { translationY }`
 * animation in `AppNavigator` then provides the GPU-only slide-in, so neither composition
 * nor layout work runs during the enter transition.
 *
 * **Dismiss flow**: [onDismissRequest] is called at the *start* of the dismiss gesture
 * (before the spring animation runs), not after it completes. This ensures that the
 * player-open flag in the shell ViewModel transitions to `false` immediately, so a quick
 * re-open tap on the mini-player correctly produces a `false → true` transition and
 * re-triggers the [LaunchedEffect] that cancels any in-flight spring and resets the
 * offset. Without this, the spring animation (up to ~1 s for a slow drag-to-threshold
 * swipe) would hold [isOpen] at `true` the entire time, making mini-player taps no-ops.
 *
 * @param isOpen Whether the player is currently logically open. Gates the [BackHandler]
 *   and [draggable] modifier to prevent interfering with other screens when off-screen,
 *   and triggers the internal offset reset when the player is reopened.
 * @param onDismissRequest Callback invoked at the start of a dismiss gesture to close
 *   the player overlay. Called before the spring animation runs so the player is
 *   logically closed immediately, even while the visual exit animation is in progress.
 * @param content Player sheet content slot.
 */
@Composable
internal fun PlayerBottomSheet(
    isOpen: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val containerSize = LocalWindowInfo.current.containerSize
    val screenHeightPx = containerSize.height.toFloat()

    val dismissThresholdPx = screenHeightPx * DISMISS_DISTANCE_FRACTION
    val dismissVelocityThresholdPx = with(density) { DISMISS_VELOCITY_THRESHOLD.toPx() }

    var verticalOffsetPx by remember { mutableFloatStateOf(0f) }
    // Holds the current spring/animate Job so a new gesture can cancel an in-flight animation.
    val verticalAnimJob = remember { object { var current: Job? = null } }

    // Reset the drag offset each time the player becomes visible. The outer
    // graphicsLayer animation in AppNavigator handles the visual slide-in; this
    // ensures PlayerBottomSheet always starts from y = 0 on re-entry, not from
    // wherever a previous swipe-dismiss left the offset.
    LaunchedEffect(isOpen) {
        if (isOpen) {
            verticalAnimJob.current?.cancel()
            verticalAnimJob.current = null
            verticalOffsetPx = 0f
        }
    }

    fun dismiss(initialVelocity: Float = 0f) {
        verticalAnimJob.current?.cancel()
        // Signal the parent immediately so the player-open flag transitions to false
        // right away. This is critical: if we only called onDismissRequest() after the
        // spring settled (up to ~1 s for a slow drag-to-threshold release), the flag
        // would stay true the whole time. Any mini-player tap during that window sets
        // it to true again — a no-op on MutableStateFlow — so the re-open is silently
        // dropped. By firing early, the next tap produces a genuine false → true
        // transition that both re-opens the overlay and triggers LaunchedEffect(isOpen)
        // to cancel any still-running spring and reset the vertical offset to 0f.
        onDismissRequest()
        verticalAnimJob.current = coroutineScope.launch {
            animate(
                initialValue = verticalOffsetPx,
                targetValue = screenHeightPx,
                initialVelocity = initialVelocity.coerceAtLeast(0f),
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            ) { value, _ -> verticalOffsetPx = value }
            // onDismissRequest() was already called above; not repeated here.
        }
    }

    fun snapBack(initialVelocity: Float = 0f) {
        verticalAnimJob.current?.cancel()
        verticalAnimJob.current = coroutineScope.launch {
            animate(
                initialValue = verticalOffsetPx,
                targetValue = 0f,
                initialVelocity = minOf(initialVelocity, 0f),
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium
                )
            ) { value, _ -> verticalOffsetPx = value.coerceAtLeast(0f) }
        }
    }

    // Gate BackHandler so it only intercepts the back button when the player is
    // actually open — not when it is sitting off-screen below the shell.
    BackHandler(enabled = isOpen) { dismiss() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .offset {
                IntOffset(
                    x = 0,
                    y = verticalOffsetPx.roundToInt()
                )
            }
            .draggable(
                enabled = isOpen,
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta ->
                    verticalOffsetPx = (verticalOffsetPx + delta).coerceAtLeast(0f)
                },
                onDragStarted = {
                    verticalAnimJob.current?.cancel()
                    verticalAnimJob.current = null
                },
                onDragStopped = { velocity ->
                    val isFlick = velocity > dismissVelocityThresholdPx
                    val isPastThreshold = velocity >= 0f && verticalOffsetPx >= dismissThresholdPx
                    if (isFlick || isPastThreshold) dismiss(initialVelocity = velocity)
                    else snapBack(initialVelocity = velocity)
                }
            )
    ) {
        content()
    }
}

private const val DISMISS_DISTANCE_FRACTION = 0.20f
private val DISMISS_VELOCITY_THRESHOLD = 80.dp
