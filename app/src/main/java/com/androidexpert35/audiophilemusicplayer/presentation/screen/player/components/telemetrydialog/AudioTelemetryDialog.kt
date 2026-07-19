package com.androidexpert35.audiophilemusicplayer.presentation.screen.player.components.telemetrydialog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioFormat
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioTelemetry
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.OutputStreamInfo
import com.androidexpert35.audiophilemusicplayer.presentation.screen.player.components.BlurredBackground
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileBlack
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileGlassHighlight
import com.androidexpert35.audiophilemusicplayer.presentation.theme.paddingMedium
import com.androidexpert35.audiophilemusicplayer.presentation.theme.paddingSMedium
import com.androidexpert35.audiophilemusicplayer.presentation.theme.paddingSSmall

/**
 * Full-screen [ModalBottomSheet] displaying source, engine, and output telemetry.
 *
 * The sheet keeps the polished liquid-glass style while reducing visual noise: the
 * primary source signal is shown first, app-owned DSP state second, output path
 * verification third.
 *
 * @param audioFormat File-level format metadata extracted from the current track.
 * @param telemetry Current real-time [AudioTelemetry] snapshot.
 * @param fallbackBitrateKbps Encoded bitrate estimate retained for API compatibility
 *   with the player telemetry entry point.
 * @param albumId MediaStore album identifier used to resolve the blurred art background.
 *   Pass `0L` to show the solid dark fallback.
 * @param onDismiss Callback invoked when the sheet is dismissed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AudioTelemetryDialog(
    audioFormat: AudioFormat,
    telemetry: AudioTelemetry,
    fallbackBitrateKbps: Int,
    albumId: Long = 0L,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.Transparent,
        scrimColor = Color.Black.copy(alpha = 0.50f),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = null,
    ) {
        TelemetrySheetContent(
            audioFormat = audioFormat,
            telemetry = telemetry,
            albumId = albumId,
            onDismiss = onDismiss,
        )
    }
}

/**
 * Stateless inner content of the telemetry bottom sheet.
 *
 * @param audioFormat File-level format metadata used by [SourceSignalCard].
 * @param telemetry Runtime telemetry snapshot used by all telemetry cards.
 * @param albumId Album identifier for the blurred art background. `0L` = no art.
 * @param onDismiss Callback forwarded to the header close button.
 */
@Composable
private fun TelemetrySheetContent(
    audioFormat: AudioFormat,
    telemetry: AudioTelemetry,
    albumId: Long,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .background(AudiophileBlack),
    ) {
        if (albumId != 0L) {
            BlurredBackground(albumId = albumId)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.30f)),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .navigationBarsPadding()
                .padding(bottom = 24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            TopEdgeHighlight()

            DragHandle()

            Spacer(modifier = Modifier.height(paddingSSmall))

            TelemetrySheetHeader(
                onDismiss = onDismiss,
            )

            Spacer(modifier = Modifier.height(paddingMedium))

            SourceSignalCard(
                audioFormat = audioFormat,
                telemetry = telemetry,
            )

            (telemetry.streamInfo as? OutputStreamInfo.Dsd)?.let { dsdStream ->
                Spacer(modifier = Modifier.height(paddingSMedium))
                DsdPlaybackInfoSection(stream = dsdStream)
            }
            Spacer(modifier = Modifier.height(paddingSMedium))
            OutputHardwareCard(telemetry = telemetry)
        }
    }
}

/**
 * Sheet title row rendered on the glass background.
 *
 * @param onDismiss Callback invoked when the close button is tapped.
 */
@Composable
private fun TelemetrySheetHeader(
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.dialog_audio_details_title),
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            fontWeight = FontWeight.Bold,
        )

        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.action_close),
                tint = Color.White.copy(alpha = 0.80f),
            )
        }
    }
}

/** Thin glass-highlight line along the top edge of the sheet. */
@Composable
private fun TopEdgeHighlight() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(
                Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        AudiophileGlassHighlight.copy(alpha = 0.55f),
                        AudiophileGlassHighlight.copy(alpha = 0.85f),
                        AudiophileGlassHighlight.copy(alpha = 0.55f),
                        Color.Transparent,
                    ),
                ),
            ),
    )
}

/** Centred drag-handle pill matching the glass-highlight tint used across bottom sheets. */
@Composable
private fun DragHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 40.dp, height = 4.dp)
                .background(
                    color = AudiophileGlassHighlight,
                    shape = RoundedCornerShape(50),
                ),
        )
    }
}
