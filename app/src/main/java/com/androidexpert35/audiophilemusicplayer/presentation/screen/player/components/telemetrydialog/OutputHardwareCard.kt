package com.androidexpert35.audiophilemusicplayer.presentation.screen.player.components.telemetrydialog

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BluetoothAudio
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioCodec
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioPathStatus
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioTelemetry
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.BitPerfectDiagnostics
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.OutputRouteKind
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.OutputStreamInfo
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileMusicPlayerTheme
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileSecondary

/**
 * Compact output-path card with a plain-language verdict for the active route.
 *
 * Direct-path and bit-perfect evidence is folded behind a disclosure row so the
 * first read explains the practical result instead of presenting diagnostic flags.
 *
 * @param telemetry Runtime audio path telemetry to render.
 */
@Composable
internal fun OutputHardwareCard(telemetry: AudioTelemetry) {
    val diagnostics = telemetry.bitPerfectDiagnostics
    val isBluetooth = diagnostics?.outputRouteKind == OutputRouteKind.BLUETOOTH
    val accentColor = if (isBluetooth) AudiophileSecondary else outputAccentColor(telemetry)

    TelemetrySection(
        icon = if (isBluetooth) Icons.Outlined.BluetoothAudio else Icons.Outlined.Memory,
        title = stringResource(
            if (isBluetooth) R.string.telemetry_section_wireless_output
            else R.string.telemetry_section_output_path
        ),
        accentColor = accentColor,
    ) {
        if (isBluetooth) {
            BluetoothOutputContent(
                telemetry = telemetry,
                deviceName = diagnostics.activeDeviceName,
            )
        } else {
            StandardOutputContent(
                telemetry = telemetry,
                diagnostics = diagnostics,
                accentColor = accentColor,
            )
        }
    }
}

/**
 * Leads with one plain-language output verdict while keeping the individual
 * direct-path and bit-perfect signals available as technical evidence.
 */
@Composable
private fun StandardOutputContent(
    telemetry: AudioTelemetry,
    diagnostics: BitPerfectDiagnostics?,
    accentColor: Color,
) {
    val pathStatus = diagnostics?.pathStatus ?: AudioPathStatus.UNKNOWN

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = diagnostics?.activeDeviceName
                    ?: stringResource(R.string.telemetry_output_device_fallback),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.telemetry_current_output),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.52f),
            )
        }
        Box(
            modifier = Modifier
                .background(accentColor.copy(alpha = 0.12f), RoundedCornerShape(50))
                .border(1.dp, accentColor.copy(alpha = 0.40f), RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 5.dp),
        ) {
            Text(
                text = standardOutputStateLabel(pathStatus),
                style = MaterialTheme.typography.labelMedium,
                color = accentColor,
                fontWeight = FontWeight.Bold,
            )
        }
    }

    Text(
        text = standardOutputExplanation(pathStatus),
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White.copy(alpha = 0.70f),
    )

    ExpandableTelemetryRow(
        title = stringResource(R.string.telemetry_output_details),
        accentColor = accentColor,
    ) {
        TelemetryValueRow(
            label = stringResource(R.string.telemetry_diagnostic_routing_tier),
            value = pathStatus.toDisplayLabel(),
        )
        diagnostics?.activeDeviceName?.let { device ->
            TelemetryValueRow(
                label = stringResource(R.string.telemetry_diagnostic_output_device),
                value = device,
            )
        }
        TelemetryStatusRow(
            label = stringResource(R.string.telemetry_direct_playback),
            activeStatus = telemetry.isDirectPlayback,
        )
        TelemetryStatusRow(
            label = stringResource(R.string.telemetry_bit_perfect),
            activeStatus = resolvedBitPerfectStatus(telemetry),
        )
    }
}

/** Returns the concise user-facing verdict for a non-Bluetooth output route. */
@Composable
private fun standardOutputStateLabel(pathStatus: AudioPathStatus): String = stringResource(
    when (pathStatus) {
        AudioPathStatus.DIRECT_BIT_PERFECT -> R.string.telemetry_output_bit_perfect_confirmed
        AudioPathStatus.DIRECT_SUPPORTED -> R.string.telemetry_output_direct
        AudioPathStatus.OEM_WARNING -> R.string.telemetry_output_direct_review
        AudioPathStatus.RESAMPLED -> R.string.telemetry_output_android_managed
        AudioPathStatus.UNKNOWN -> R.string.telemetry_output_checking
    }
)

/** Explains the practical consequence of the current non-Bluetooth route. */
@Composable
private fun standardOutputExplanation(pathStatus: AudioPathStatus): String = stringResource(
    when (pathStatus) {
        AudioPathStatus.DIRECT_BIT_PERFECT -> R.string.telemetry_output_bit_perfect_explanation
        AudioPathStatus.DIRECT_SUPPORTED -> R.string.telemetry_output_direct_explanation
        AudioPathStatus.OEM_WARNING -> R.string.telemetry_output_direct_review_explanation
        AudioPathStatus.RESAMPLED -> R.string.telemetry_output_android_managed_explanation
        AudioPathStatus.UNKNOWN -> R.string.telemetry_output_checking_explanation
    }
)

/**
 * Presents the Bluetooth boundary without claiming a final codec format that
 * Android does not expose reliably to applications.
 */
@Composable
private fun BluetoothOutputContent(
    telemetry: AudioTelemetry,
    deviceName: String?,
) {
    val engineOutput = outputPipelineShape(telemetry.streamInfo)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = deviceName ?: stringResource(R.string.telemetry_bluetooth_audio),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.telemetry_bluetooth_audio),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.52f),
            )
        }
        Box(
            modifier = Modifier
                .background(AudiophileSecondary.copy(alpha = 0.12f), RoundedCornerShape(50))
                .border(1.dp, AudiophileSecondary.copy(alpha = 0.40f), RoundedCornerShape(50))
                .padding(horizontal = 12.dp, vertical = 5.dp),
        ) {
            Text(
                text = stringResource(R.string.telemetry_system_managed),
                style = MaterialTheme.typography.labelMedium,
                color = AudiophileSecondary,
                fontWeight = FontWeight.Bold,
            )
        }
    }

    Text(
        text = stringResource(R.string.telemetry_bluetooth_summary, engineOutput),
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White.copy(alpha = 0.70f),
    )
}

@Preview(name = "Bluetooth system-managed output", showBackground = true, backgroundColor = 0xFF090B10)
@Composable
private fun BluetoothOutputCardPreview() {
    AudiophileMusicPlayerTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            OutputHardwareCard(
                telemetry = AudioTelemetry(
                    streamInfo = OutputStreamInfo.Pcm(
                        codec = AudioCodec.FLAC,
                        sampleRateHz = 96_000,
                        bitDepth = 32,
                        bitrateKbps = 4_608,
                    ),
                    bitPerfectDiagnostics = BitPerfectDiagnostics(
                        pathStatus = AudioPathStatus.RESAMPLED,
                        activeDeviceName = "OnePlus Buds 4",
                        outputRouteKind = OutputRouteKind.BLUETOOTH,
                    ),
                    isAudiophileEngineActive = true,
                )
            )
        }
    }
}

@Preview(name = "Android-managed output", showBackground = true, backgroundColor = 0xFF090B10)
@Composable
private fun AndroidManagedOutputCardPreview() {
    AudiophileMusicPlayerTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            OutputHardwareCard(
                telemetry = AudioTelemetry(
                    streamInfo = OutputStreamInfo.Pcm(
                        codec = AudioCodec.FLAC,
                        sampleRateHz = 96_000,
                        bitDepth = 32,
                        bitrateKbps = 4_608,
                    ),
                    bitPerfectDiagnostics = BitPerfectDiagnostics(
                        pathStatus = AudioPathStatus.RESAMPLED,
                        activeDeviceName = "Altoparlante integrato",
                        outputRouteKind = OutputRouteKind.BUILT_IN,
                    ),
                    isAudiophileEngineActive = true,
                )
            )
        }
    }
}

