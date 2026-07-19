package com.androidexpert35.audiophilemusicplayer.presentation.screen.common.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileGlassHighlight
import com.androidexpert35.audiophilemusicplayer.presentation.theme.MotionTokens
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.shell.AppShellUiModel

/**
 * Floating glassmorphism panel that composes the mini-player bar and bottom
 * navigation bar into a single surface anchored at the bottom of the screen.
 *
 * The outer [Box] is measured by [onHeightChanged] outside [AnimatedVisibility]
 * so the measured height remains stable during the fade-out transition and the
 * [com.androidexpert35.audiophilemusicplayer.presentation.screen.common.LocalShellBottomPadding]
 * value never jumps while the panel is animating.
 *
 * @param model Current immutable shell UI state supplying track, route, and playback status.
 * @param visible Whether the panel is shown; drives the outer [AnimatedVisibility].
 * @param onPlayPauseClick Callback for the mini-player play/pause action.
 * @param onSkipPreviousClick Callback for the mini-player skip-previous action.
 * @param onSkipNextClick Callback for the mini-player skip-next action.
 * @param onMiniPlayerClick Callback that opens the full player overlay.
 * @param onDestinationSelected Callback emitted when a bottom-nav item is tapped.
 * @param onHeightChanged Reports the panel's measured pixel height so the parent
 *   can keep [com.androidexpert35.audiophilemusicplayer.presentation.screen.common.LocalShellBottomPadding]
 *   up to date.
 * @param modifier Optional [Modifier] applied to the outermost measuring container.
 */
@Composable
internal fun ShellBottomPanel(
    model: AppShellUiModel,
    visible: Boolean,
    onPlayPauseClick: () -> Unit,
    onSkipPreviousClick: () -> Unit,
    onSkipNextClick: () -> Unit,
    onMiniPlayerClick: () -> Unit,
    onDestinationSelected: (BottomNavDestination) -> Unit,
    onHeightChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentTrack = model.playbackState.currentTrack
    val panelShape = RoundedCornerShape(32.dp)

    // Measuring Box sits outside AnimatedVisibility so panelHeightPx stays stable
    // while the panel fades out, preventing a layout jump during the exit animation.
    Box(modifier = modifier.onSizeChanged { onHeightChanged(it.height) }) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(MotionTokens.DurationShort)),
            exit = fadeOut(tween(MotionTokens.DurationShort))
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .border(width = 1.dp, color = AudiophileGlassHighlight, shape = panelShape),
                shape = panelShape,
                color = MaterialTheme.colorScheme.surfaceContainer,
                tonalElevation = 0.dp,
                shadowElevation = 12.dp
            ) {
                Column {
                    AnimatedVisibility(
                        visible = currentTrack != null,
                        enter = expandVertically(
                            animationSpec = tween(MotionTokens.DurationMedium),
                            expandFrom = Alignment.Top
                        ) + fadeIn(tween(MotionTokens.DurationMedium)),
                        exit = shrinkVertically(
                            animationSpec = tween(MotionTokens.DurationShort),
                            shrinkTowards = Alignment.Top
                        ) + fadeOut(tween(MotionTokens.DurationShort))
                    ) {
                        currentTrack?.let { track ->
                            MiniPlayerBar(
                                track = track,
                                playbackStatus = model.playbackState.status,
                                onPlayPauseClick = onPlayPauseClick,
                                onSkipPreviousClick = onSkipPreviousClick,
                                onSkipNextClick = onSkipNextClick,
                                onMiniPlayerClick = onMiniPlayerClick
                            )
                        }
                    }

                    AppBottomNavBar(
                        currentRoute = model.currentRoute,
                        isPlayerOpen = model.isPlayerOpen,
                        onDestinationSelected = onDestinationSelected,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

