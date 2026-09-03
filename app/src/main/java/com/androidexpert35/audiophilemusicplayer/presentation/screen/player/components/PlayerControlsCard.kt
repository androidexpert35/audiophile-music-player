package com.androidexpert35.audiophilemusicplayer.presentation.screen.player.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.androidexpert35.audiophilemusicplayer.domain.model.analysis.StationaryAnalysis
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioFormat
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioTelemetry
import com.androidexpert35.audiophilemusicplayer.domain.model.playback.PlaybackStatus
import com.androidexpert35.audiophilemusicplayer.domain.model.playback.RepeatMode
import com.androidexpert35.audiophilemusicplayer.domain.model.playback.ShuffleMode
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileGlassHighlight
import com.androidexpert35.audiophilemusicplayer.presentation.theme.paddingMedium
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.player.PlayerUiEvent

/**
 * Frosted-glass surface card stacking the track metadata row, audio telemetry,
 * seek bar, and transport controls into a single cohesive panel.
 *
 * The card uses a semi-transparent `surfaceContainer`
 * fill with a 1 dp [AudiophileGlassHighlight] border to achieve the glazed look
 * consistent with the rest of the immersive player layout.
 *
 * All recomposition scopes are kept narrow:
 * - [telemetryState] is not read in this composable; only [PlayerTelemetrySection]
 *   and [AudioInfoRow] observe it.
 * - [positionState] is not read here; only [SeekBar] reads it through lambdas.
 *
 * @param supportingText Pre-computed artist / album secondary line for [TrackInfoRow].
 * @param isFavorite Whether the current track is marked as a favourite.
 * @param playbackStatus Current [PlaybackStatus] driving the play/pause icon.
 * @param shuffleMode Current [ShuffleMode] controlling the shuffle chip highlight.
 * @param repeatMode Current [RepeatMode] cycling the repeat chip icon.
 * @param audioFormat File-level audio format metadata for the current track.
 * @param telemetryState Isolated telemetry [State] forwarded to [PlayerTelemetrySection]
 *   without being read in this composable.
 * @param measuredSignalState Isolated [State] of the current track's cached offline
 *   measurements, forwarded without being read here.
 * @param fallbackBitrateKbps Estimated encoded bitrate shown before runtime telemetry
 *   arrives.
 * @param albumId MediaStore album identifier forwarded to the telemetry sheet so its
 *   blurred background reflects the currently playing album art.
 * @param positionState Live position + duration [State] forwarded to [SeekBar] as lambdas.
 * @param onEvent Callback emitting player intents to the ViewModel.
 * @param modifier Optional [Modifier] applied to the outer [Surface].
 */
@Composable
internal fun PlayerControlsCard(
    supportingText: String,
    isFavorite: Boolean,
    playbackStatus: PlaybackStatus,
    shuffleMode: ShuffleMode,
    repeatMode: RepeatMode,
    audioFormat: AudioFormat,
    telemetryState: State<AudioTelemetry>,
    measuredSignalState: State<StationaryAnalysis?>,
    fallbackBitrateKbps: Int,
    albumId: Long = 0L,
    positionState: State<Pair<Long, Long>>,
    onEvent: (PlayerUiEvent) -> Unit,
    title: String,
    trackId: Long,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .border(
                width = 1.dp,
                color = AudiophileGlassHighlight,
                shape = MaterialTheme.shapes.extraLarge
            ),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.45f),
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(paddingMedium),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            TrackInfoRow(
                title = title,
                supportingText = supportingText,
                isFavorite = isFavorite,
                onLikeClick = { onEvent(PlayerUiEvent.ToggleLikeSong(trackId)) }
            )

            // telemetryState is passed without reading .value here — only
            // AudioInfoRow (inside PlayerTelemetrySection) subscribes.
            // Dialog state lives inside PlayerTelemetrySection.
            PlayerTelemetrySection(
                audioFormat = audioFormat,
                telemetryState = telemetryState,
                measuredSignalState = measuredSignalState,
                fallbackBitrateKbps = fallbackBitrateKbps,
                albumId = albumId,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            SeekBar(
                positionMs = { positionState.value.first },
                durationMs = { positionState.value.second },
                onEvent = onEvent,
                modifier = Modifier.fillMaxWidth()
            )

            PlaybackControls(
                playbackStatus = playbackStatus,
                shuffleMode = shuffleMode,
                repeatMode = repeatMode,
                onEvent = onEvent
            )
        }
    }
}

