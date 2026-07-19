package com.androidexpert35.audiophilemusicplayer.presentation.screen.player.components.telemetrydialog

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophilePrimary
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileSecondary
import com.androidexpert35.audiophilemusicplayer.presentation.theme.HiResGold

/**
 * Hero card that merges file-source metadata with the high-level signal path.
 *
 * The first glance shows only the important source shape and the transformed
 * output shape; detailed file and carrier rows are tucked behind a local
 * disclosure row to keep the telemetry sheet calm.
 *
 * @param audioFormat Encoded file metadata for the current track.
 * @param telemetry Runtime output telemetry from the playback engine.
 */
@Composable
internal fun SourceSignalCard(
    audioFormat: AudioFormat,
    telemetry: AudioTelemetry,
) {
    TelemetrySection(
        icon = Icons.Outlined.AccountTree,
        title = stringResource(R.string.telemetry_section_source_signal),
        accentColor = AudiophilePrimary,
    ) {
        SourceHeroSummary(audioFormat = audioFormat)
        SignalFlowStrip(
            audioFormat = audioFormat,
            telemetry = telemetry,
        )
    }
}

@Composable
private fun SourceHeroSummary(audioFormat: AudioFormat) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = audioFormat.codec.displayName,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(
            horizontalAlignment = Alignment.End,
        ) {
            SourceQualityPill(audioFormat = audioFormat)
        }
    }
}

@Composable
private fun SourceQualityPill(audioFormat: AudioFormat) {
    val color = if (audioFormat.isLossless) HiResGold else AudiophileSecondary
    val label = stringResource(
        if (audioFormat.isLossless) R.string.telemetry_source_lossless
        else R.string.telemetry_source_lossy
    )

    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun SignalFlowStrip(
    audioFormat: AudioFormat,
    telemetry: AudioTelemetry,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {

        SignalPathNode(
            stageNumber = "01",
            label = stringResource(R.string.telemetry_section_source),
            value = formatPcmShape(audioFormat.bitDepth, audioFormat.sampleRateHz),
            color = if (audioFormat.isLossless) HiResGold else AudiophileSecondary,
        )
        SignalPathConnector()
        SignalPathNode(
            stageNumber = "02",
            label = stringResource(R.string.telemetry_pipeline_engine),
            value = playbackEngineLabel(telemetry),
            color = AudiophilePrimary,
        )
        SignalPathConnector()
        SignalPathNode(
            stageNumber = "03",
            label = stringResource(R.string.telemetry_pipeline_processing),
            value = activeProcessingLabel(telemetry),
            color = if (hasActiveProcessing(telemetry)) AudiophilePrimary else Color.White.copy(alpha = 0.45f),
        )
        SignalPathConnector()
        SignalPathNode(
            stageNumber = "04",
            label = stringResource(R.string.telemetry_resampler),
            value = resamplerPathLabel(telemetry),
                color = resamplerPathColor(telemetry),
        )
        SignalPathConnector()
        SignalPathNode(
            stageNumber = "05",
            label = stringResource(R.string.telemetry_pipeline_engine_output),
            value = outputPipelineShape(telemetry.streamInfo),
            color = outputAccentColor(telemetry),
            supportingText = engineOutputDestinationLabel(telemetry),
        )
    }
}

@Composable
private fun SignalPathNode(
    modifier: Modifier = Modifier,
    stageNumber: String,
    label: String,
    value: String,
    color: Color,
    supportingText: String? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = color.copy(alpha = 0.10f),
        tonalElevation = 0.dp,
        border = BorderStroke(1.dp, color.copy(alpha = 0.24f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                color.copy(alpha = 0.34f),
                                color.copy(alpha = 0.10f),
                            ),
                        ),
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stageNumber,
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    fontWeight = FontWeight.Black,
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.56f),
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleSmall,
                    color = color,
                    fontWeight = FontWeight.Bold,
                )
                supportingText?.let { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.48f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SignalPathConnector() {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .padding(start = 29.dp)
                .size(width = 2.dp, height = 14.dp)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.08f),
                            AudiophilePrimary.copy(alpha = 0.38f),
                            Color.White.copy(alpha = 0.08f),
                        ),
                    ),
                    shape = RoundedCornerShape(50),
                ),
        )
    }
}


@Composable
private fun playbackEngineLabel(telemetry: AudioTelemetry): String = if (
    telemetry.isAudiophileEngineActive
) {
    stringResource(R.string.telemetry_pipeline_audiophile_engine)
} else {
    stringResource(R.string.telemetry_pipeline_android_engine)
}

@Composable
private fun resamplerPathLabel(telemetry: AudioTelemetry): String = when {
    telemetry.isSoxrActive -> stringResource(R.string.telemetry_resampler_sox)
    telemetry.bitPerfectDiagnostics?.isDirectUsbBypass == true -> stringResource(R.string.telemetry_resampler_native)
    else -> stringResource(R.string.telemetry_resampler_android_system)
}

private fun resamplerPathColor(telemetry: AudioTelemetry): Color = when {
    telemetry.isSoxrActive -> AudiophilePrimary
    telemetry.bitPerfectDiagnostics?.isDirectUsbBypass == true -> HiResGold
    else -> AudiophileSecondary
}

@Composable
private fun engineOutputDestinationLabel(telemetry: AudioTelemetry): String = stringResource(
    if (telemetry.bitPerfectDiagnostics?.isDirectUsbBypass == true) {
        R.string.telemetry_pipeline_sent_to_dac
    } else {
        R.string.telemetry_pipeline_sent_to_android
    }
)

