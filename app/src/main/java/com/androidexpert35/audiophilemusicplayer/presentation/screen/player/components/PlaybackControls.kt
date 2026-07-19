package com.androidexpert35.audiophilemusicplayer.presentation.screen.player.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.domain.model.playback.PlaybackStatus
import com.androidexpert35.audiophilemusicplayer.domain.model.playback.RepeatMode
import com.androidexpert35.audiophilemusicplayer.domain.model.playback.ShuffleMode
import com.androidexpert35.audiophilemusicplayer.presentation.theme.MotionTokens
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.player.PlayerUiEvent

/**
 * Transport controls row: Shuffle, Previous, Play/Pause, Next, Repeat.
 *
 * Five evenly-spaced icons with the central play/pause rendered as a large
 * filled circle button. Skip buttons are generously sized for comfortable
 * touch targets while shuffle/repeat remain compact as secondary actions.
 *
 * @param playbackStatus Current [PlaybackStatus] — drives the play/pause icon.
 * @param shuffleMode Current [ShuffleMode] — highlights the shuffle icon when active.
 * @param repeatMode Current [RepeatMode] — cycles through OFF → ALL → ONE icons.
 * @param onEvent Callback to emit [PlayerUiEvent]s for each control action.
 * @param modifier Optional [Modifier] for the root row.
 */
@Composable
internal fun PlaybackControls(
    playbackStatus: PlaybackStatus,
    shuffleMode: ShuffleMode,
    repeatMode: RepeatMode,
    onEvent: (PlayerUiEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    // Subtle scale pulse on the play/pause button when playback state changes
    val isPlaying = playbackStatus == PlaybackStatus.PLAYING
    val playPauseScale by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 1f,
        animationSpec = tween(
            durationMillis = MotionTokens.DurationMedium,
            easing = MotionTokens.EasingEmphasized
        ),
        label = "play_pause_scale"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // --- Shuffle ---
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center
        ) {
            IconButton(
                onClick = {
                    val next = if (shuffleMode == ShuffleMode.OFF) ShuffleMode.ON else ShuffleMode.OFF
                    onEvent(PlayerUiEvent.SetShuffleMode(next))
                },
                modifier = Modifier.size(44.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = if (shuffleMode == ShuffleMode.ON) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f)
                    }
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.Shuffle,
                    contentDescription = stringResource(R.string.cd_shuffle),
                    tint = if (shuffleMode == ShuffleMode.ON) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        // --- Skip Previous ---
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center
        ) {
            IconButton(
                onClick = { onEvent(PlayerUiEvent.SkipPrevious) },
                modifier = Modifier.size(52.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f)
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.SkipPrevious,
                    contentDescription = stringResource(R.string.cd_skip_previous),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        // --- Play / Pause (hero button) ---
        Box(
            modifier = Modifier.weight(1.4f),
            contentAlignment = Alignment.Center
        ) {
            FilledIconButton(
                onClick = {
                    when (playbackStatus) {
                        PlaybackStatus.PLAYING -> onEvent(PlayerUiEvent.Pause)
                        // During BUFFERING the engine is loading. If the user taps play
                        // anyway (e.g. the track has been silent for longer than expected),
                        // issuing a Resume gives PlaybackController a chance to run
                        // maybeRecoverResumeStall and nudge a stalled pipeline back to life.
                        else -> onEvent(PlayerUiEvent.Resume)
                    }
                },
                modifier = Modifier
                    .size(72.dp)
                    .scale(playPauseScale),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                AnimatedContent(
                    targetState = isPlaying,
                    transitionSpec = {
                        (fadeIn(tween(MotionTokens.DurationShort)) +
                            scaleIn(tween(MotionTokens.DurationShort), initialScale = 0.75f))
                            .togetherWith(
                                fadeOut(tween(150)) +
                                    scaleOut(tween(150), targetScale = 0.75f)
                            )
                    },
                    label = "play_pause_icon"
                ) { playing ->
                    Icon(
                        imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(
                            if (playing) R.string.cd_pause else R.string.cd_play
                        ),
                        modifier = Modifier.size(34.dp)
                    )
                }
            }
        }

        // --- Skip Next ---
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center
        ) {
            IconButton(
                onClick = { onEvent(PlayerUiEvent.SkipNext) },
                modifier = Modifier.size(52.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.65f)
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.SkipNext,
                    contentDescription = stringResource(R.string.cd_skip_next),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        // --- Repeat ---
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center
        ) {
            IconButton(
                onClick = {
                    val next = when (repeatMode) {
                        RepeatMode.OFF -> RepeatMode.ALL
                        RepeatMode.ALL -> RepeatMode.ONE
                        RepeatMode.ONE -> RepeatMode.OFF
                    }
                    onEvent(PlayerUiEvent.SetRepeatMode(next))
                },
                modifier = Modifier.size(44.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = if (repeatMode != RepeatMode.OFF) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f)
                    }
                )
            ) {
                Icon(
                    imageVector = when (repeatMode) {
                        RepeatMode.ONE -> Icons.Filled.RepeatOne
                        else -> Icons.Filled.Repeat
                    },
                    contentDescription = stringResource(R.string.cd_repeat),
                    tint = if (repeatMode != RepeatMode.OFF) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}


