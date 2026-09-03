package com.androidexpert35.audiophilemusicplayer.presentation.screen.player.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.androidexpert35.audiophilemusicplayer.domain.model.analysis.StationaryAnalysis
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioFormat
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioTelemetry
import com.androidexpert35.audiophilemusicplayer.presentation.screen.player.components.telemetrydialog.AudioTelemetryDialog

/**
 * Self-contained player section that renders the audio telemetry chip row and owns
 * the telemetry detail dialog's visibility state locally.
 *
 * Keeping `showDialog` inside this composable means toggling the dialog does **not**
 * recompose `PlayerContent` or any of its siblings — only this narrow wrapper recomposes.
 *
 * @param audioFormat File-level audio format metadata for the current track.
 * @param telemetryState Isolated telemetry [State] passed from [PlayerScreen] without
 *   being read in [PlayerContent]. [AudioInfoRow] reads [State.value] internally,
 *   scoping telemetry recompositions to just that row.
 * @param measuredSignalState Isolated [State] carrying the cached offline measurements
 *   of the current track. Read only when the sheet is open, so a track change never
 *   recomposes the chip row over it.
 * @param fallbackBitrateKbps Estimated encoded bitrate displayed before runtime
 *   telemetry becomes available.
 * @param albumId MediaStore album identifier forwarded to the telemetry sheet's
 *   blurred-background so the glass panel reflects the current album art.
 * @param modifier Optional [Modifier] for the chip row.
 */
@Composable
internal fun PlayerTelemetrySection(
    audioFormat: AudioFormat,
    telemetryState: State<AudioTelemetry>,
    measuredSignalState: State<StationaryAnalysis?>,
    fallbackBitrateKbps: Int,
    albumId: Long = 0L,
    modifier: Modifier = Modifier
) {
    // Local state — toggling the dialog recomposes only this composable.
    var showDialog by remember { mutableStateOf(false) }

    AudioInfoRow(
        audioFormat = audioFormat,
        telemetryState = telemetryState,
        fallbackBitrateKbps = fallbackBitrateKbps,
        onInfoClick = { showDialog = true },
        modifier = modifier
    )

    if (showDialog) {
        // Read telemetryState.value here: it is already subscribed in AudioInfoRow so
        // reading it in the sheet does not create an additional subscriber scope.
        AudioTelemetryDialog(
            audioFormat = audioFormat,
            telemetry = telemetryState.value,
            // Read only inside the open sheet: while it is closed nothing subscribes
            // to the measurement state, so a track change cannot recompose the row.
            measuredSignal = measuredSignalState.value,
            fallbackBitrateKbps = fallbackBitrateKbps,
            albumId = albumId,
            onDismiss = { showDialog = false }
        )
    }
}

