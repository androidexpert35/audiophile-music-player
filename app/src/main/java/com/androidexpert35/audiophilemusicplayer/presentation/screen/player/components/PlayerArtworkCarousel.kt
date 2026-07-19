package com.androidexpert35.audiophilemusicplayer.presentation.screen.player.components

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track
import com.androidexpert35.audiophilemusicplayer.presentation.theme.MotionTokens
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Shows now-playing artwork as a touch-driven, fixed-size carousel.
 *
 * The artwork follows the user's finger directly. The adjacent cover is rendered beside
 * the current cover inside a clipped viewport, so the handoff is a page-to-page slide
 * rather than an overlapping crossfade. Player metadata, transport controls, and the
 * enclosing layout remain stationary throughout.
 *
 * @param track Currently selected queue track whose artwork is shown.
 * @param queueTracks Ordered queue used to determine whether the transition is forward
 *   or backward.
 * @param onTrackClick Callback invoked with the visible artwork's track when it is tapped.
 * @param onSkipNext Callback invoked after the next artwork finishes sliding into view.
 * @param onSkipPrevious Callback invoked after the previous artwork finishes sliding into view.
 * @param modifier Modifier applied to the fixed carousel viewport.
 */
@Composable
internal fun PlayerArtworkCarousel(
    track: Track,
    queueTracks: List<Track>,
    onTrackClick: (Track) -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val swipeVelocityThresholdPx = with(density) {
        TRACK_CHANGE_VELOCITY_THRESHOLD.toPx()
    }
    val artworkGapPx = with(density) { ARTWORK_PAGE_GAP.toPx() }
    val currentIndex = queueTracks.indexOfFirst { it.id == track.id }
    val previousTrack = queueTracks.getOrNull(currentIndex - 1)
    val nextTrack = queueTracks.getOrNull(currentIndex + 1)

    var viewportWidthPx by remember { mutableFloatStateOf(0f) }
    var horizontalOffsetPx by remember { mutableFloatStateOf(0f) }
    val settleAnimationJob = remember { object { var current: Job? = null } }
    val pageDistancePx = viewportWidthPx + artworkGapPx

    // When playback changes (including through the transport controls), the new current
    // cover must start centred rather than inheriting a completed swipe offset.
    LaunchedEffect(track.id) {
        settleAnimationJob.current?.cancel()
        settleAnimationJob.current = null
        horizontalOffsetPx = 0f
    }

    fun settleAt(targetOffsetPx: Float, initialVelocity: Float, onFinished: () -> Unit = {}) {
        settleAnimationJob.current?.cancel()
        settleAnimationJob.current = coroutineScope.launch {
            animate(
                initialValue = horizontalOffsetPx,
                targetValue = targetOffsetPx,
                initialVelocity = initialVelocity,
                animationSpec = tween(
                    durationMillis = MotionTokens.DurationMedium,
                    easing = MotionTokens.EasingStandard
                )
            ) { value, _ ->
                horizontalOffsetPx = value
            }
            onFinished()
        }
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { viewportWidthPx = it.width.toFloat() }
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    val minOffset = if (nextTrack == null) 0f else -pageDistancePx
                    val maxOffset = if (previousTrack == null) 0f else pageDistancePx
                    horizontalOffsetPx = (horizontalOffsetPx + delta).coerceIn(minOffset, maxOffset)
                },
                onDragStarted = {
                    settleAnimationJob.current?.cancel()
                    settleAnimationJob.current = null
                },
                onDragStopped = { velocity ->
                    val action = PlayerSwipeActionResolver.resolve(
                        horizontalOffsetPx = horizontalOffsetPx,
                        velocityPxPerSecond = velocity,
                        distanceThresholdPx = viewportWidthPx * TRACK_CHANGE_DISTANCE_FRACTION,
                        velocityThresholdPx = swipeVelocityThresholdPx
                    )
                    when (action) {
                        PlayerSwipeAction.NEXT -> if (nextTrack != null) {
                            settleAt(-pageDistancePx, velocity, onSkipNext)
                        } else {
                            settleAt(0f, velocity)
                        }

                        PlayerSwipeAction.PREVIOUS -> if (previousTrack != null) {
                            settleAt(pageDistancePx, velocity, onSkipPrevious)
                        } else {
                            settleAt(0f, velocity)
                        }

                        null -> settleAt(0f, velocity)
                    }
                }
            )
    ) {
        // Keep both neighbouring pages composed just outside the viewport. Besides making the
        // carousel a true row of adjacent pages, this starts image loading before either
        // direction is dragged into view.
        previousTrack?.let { previous ->
            AlbumArtwork(
                albumId = previous.albumId,
                albumTitle = previous.albumTitle,
                localArtUri = previous.artUri,
                onClick = previous.albumId
                    .takeIf { it != 0L }
                    ?.let { { onTrackClick(previous) } },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = horizontalOffsetPx - pageDistancePx }
            )
        }

        nextTrack?.let { next ->
            AlbumArtwork(
                albumId = next.albumId,
                albumTitle = next.albumTitle,
                localArtUri = next.artUri,
                onClick = next.albumId
                    .takeIf { it != 0L }
                    ?.let { { onTrackClick(next) } },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationX = horizontalOffsetPx + pageDistancePx }
            )
        }

        AlbumArtwork(
            albumId = track.albumId,
            albumTitle = track.albumTitle,
            localArtUri = track.artUri,
            onClick = track.albumId
                .takeIf { it != 0L }
                ?.let { { onTrackClick(track) } },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = horizontalOffsetPx }
        )
    }
}

private const val TRACK_CHANGE_DISTANCE_FRACTION = 0.18f
private val TRACK_CHANGE_VELOCITY_THRESHOLD = 800.dp
private val ARTWORK_PAGE_GAP = 12.dp
