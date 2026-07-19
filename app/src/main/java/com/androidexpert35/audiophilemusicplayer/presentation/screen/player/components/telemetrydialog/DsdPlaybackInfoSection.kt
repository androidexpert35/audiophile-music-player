package com.androidexpert35.audiophilemusicplayer.presentation.screen.player.components.telemetrydialog

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.OutputStreamInfo
import com.androidexpert35.audiophilemusicplayer.presentation.theme.HiResGold

/**
 * DSD-only playback details shown when the active stream uses a DSD transport tier.
 *
 * This restores the specialized DSD visibility without reintroducing a generic,
 * redundant signal-path card for PCM playback.
 *
 * @param stream Active DSD stream telemetry to render.
 */
@Composable
internal fun DsdPlaybackInfoSection(stream: OutputStreamInfo.Dsd) {
    TelemetrySection(
        icon = Icons.Outlined.GraphicEq,
        title = stringResource(R.string.settings_dsd_section_title),
        accentColor = HiResGold,
    ) {
        TelemetryValueRow(
            label = stringResource(R.string.telemetry_dsd_source_format),
            value = stream.sourceContainer ?: stringResource(R.string.telemetry_unavailable),
        )
        TelemetryValueRow(
            label = stringResource(R.string.telemetry_dsd_rate),
            value = formatDsdRate(stream.sourceDsdRate),
            badge = true,
            badgeColor = HiResGold,
        )
        TelemetryValueRow(
            label = stringResource(R.string.telemetry_dsd_output_mode),
            value = formatDsdOutputMode(
                mode = stream.outputMode,
                isResampled = stream.isResampled,
            ),
            badge = true,
            badgeColor = HiResGold,
        )
        if (stream.pcmOutput == null) {
            TelemetryValueRow(
                label = stringResource(R.string.telemetry_dsd_effective_rate),
                value = formatDsdRate(stream.sourceDsdRate),
            )
        }
        stream.pcmOutput?.let { pcm ->
            TelemetryValueRow(
                label = stringResource(R.string.telemetry_dop_carrier),
                value = formatDopCarrier(
                    bitDepth = pcm.bitDepth,
                    sampleRateHz = pcm.sampleRateHz,
                ),
            )
        }
        if (stream.isResampled) {
            Text(
                text = stringResource(R.string.telemetry_resampled_dsd_note),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.55f),
            )
        }
    }
}

