package com.androidexpert35.audiophilemusicplayer.presentation.screen.player.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioCodec
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioFormat
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioTelemetry
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.DsdOutputMode
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.DsdRate
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.OutputStreamInfo
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudioChipBackground
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileGlassHighlight
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileMusicPlayerTheme
import com.androidexpert35.audiophilemusicplayer.presentation.theme.HiResGold
import com.androidexpert35.audiophilemusicplayer.presentation.theme.LossyGrey
import java.util.Locale

/**
 * Compact centered single-pill telemetry chip shown below the track metadata.
 *
 * Combines the most relevant playback figures — sample rate, bit depth, bitrate
 * (or DSD rate / bit-clock for native-DSD material) — into one Material-style
 * pill chip. A trailing info icon opens the detailed telemetry dialog.
 *
 * The pill adopts the same borderless shape and colour logic used by the
 * quality tag in MiniPlayerBar: fully-rounded background pill, [HiResGold] /
 * [LossyGrey] accent colours, and `labelSmall + SemiBold` typography.
 *
 * ## Telemetry isolation
 * [telemetryState] is read via [State.value] **inside this composable's body**, not
 * in the parent. This scopes telemetry recompositions to just this row — album art,
 * seek bar, and transport controls remain stable on every telemetry tick.
 *
 * @param audioFormat The file-level audio format metadata.
 * @param telemetryState Live [State] wrapping the runtime playback telemetry from Media3.
 *   Reading [State.value] here — not in the parent — isolates recomposition to this row.
 * @param fallbackBitrateKbps Estimated encoded bitrate used until runtime telemetry becomes available.
 * @param onInfoClick Callback when the user taps the chip.
 * @param modifier Optional [Modifier] for the root container.
 */
@Composable
internal fun AudioInfoRow(
    audioFormat: AudioFormat,
    telemetryState: State<AudioTelemetry>,
    fallbackBitrateKbps: Int,
    onInfoClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val telemetry = telemetryState.value

    // Extract format details from the typed stream info — PCM and DSD handled separately.
    val streamInfo = telemetry.streamInfo
    val pcmInfo = streamInfo as? OutputStreamInfo.Pcm
    val dsdInfo = streamInfo as? OutputStreamInfo.Dsd

    val resolvedSampleRateHz = pcmInfo?.sampleRateHz?.takeIf { it > 0 } ?: audioFormat.sampleRateHz
    val resolvedBitDepth     = pcmInfo?.bitDepth?.takeIf    { it > 0 } ?: audioFormat.bitDepth
    val resolvedBitrateKbps  = pcmInfo?.bitrateKbps?.takeIf { it > 0 } ?: fallbackBitrateKbps

    val isHiRes = audioFormat.isLossless ||
        resolvedBitDepth >= 24 ||
        resolvedSampleRateHz >= 48_000 ||
        dsdInfo != null

    val tagColor = if (isHiRes) HiResGold else LossyGrey
    val backgroundColor = if (isHiRes) AudioChipBackground else LossyGrey.copy(alpha = 0.1f)


    // Build a single combined label — segments joined by a centered dot separator.
    val combinedLabel = if (dsdInfo != null) {
        listOf(
            dsdInfo.sourceDsdRate.displayName,
            DSD_BIT_DEPTH_LABEL,
            formatDsdBitClockLabel(dsdInfo.sourceDsdRate.sampleRateHz)
        ).joinToString(CHIP_SEPARATOR)
    } else {
        listOfNotNull(
            formatBitDepthLabel(resolvedBitDepth).takeIf { resolvedBitDepth > 0 },
            formatSampleRateLabel(resolvedSampleRateHz).takeIf { resolvedSampleRateHz > 0 },
            formatBitrateLabel(resolvedBitrateKbps).takeIf { resolvedBitrateKbps > 0 }
        ).joinToString(CHIP_SEPARATOR)
    }

    Row(
        modifier = modifier
            .padding(top = 2.dp)
            .clip(MaterialTheme.shapes.small)
            .border(width = 1.dp, color = AudiophileGlassHighlight, shape = MaterialTheme.shapes.small)
            .background(backgroundColor)
            .clickable(onClick = onInfoClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (combinedLabel.isNotEmpty()) {
            Text(
                text = combinedLabel,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                color = tagColor
            )
            Spacer(modifier = Modifier.size(6.dp))
        }
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = stringResource(R.string.cd_audio_info),
            tint = tagColor,
            modifier = Modifier.size(13.dp)
        )
    }
}

private fun formatSampleRateLabel(sampleRateHz: Int): String {
    if (sampleRateHz <= 0) return "-- KHZ"

    val sampleRateKhz = sampleRateHz / 1000f
    return if (sampleRateKhz == sampleRateKhz.toLong().toFloat()) {
        "${sampleRateKhz.toLong()} KHZ"
    } else {
        "${String.format(Locale.US, "%.1f", sampleRateKhz).replace('.', ',')} KHZ"
    }
}

private fun formatBitDepthLabel(bitDepth: Int): String =
    if (bitDepth > 0) "$bitDepth-BIT" else "-- BIT"

private fun formatBitrateLabel(bitrateKbps: Int): String =
    if (bitrateKbps > 0) "$bitrateKbps KBPS" else "-- KBPS"

/** Fixed 1-bit label for DSD source material. */
private const val DSD_BIT_DEPTH_LABEL = "1-BIT"

/** Separator token used between telemetry segments inside the combined chip label. */
private const val CHIP_SEPARATOR = " · "

/**
 * Formats the one-bit DSD bit-clock rate as a compact MHz label.
 *
 * DSD64 → 2822400 Hz → `"2,8 MHZ"`, DSD128 → `"5,6 MHZ"`, DSD256 → `"11,3 MHZ"`.
 * The comma separator matches the rest of the info-row chips.
 */
private fun formatDsdBitClockLabel(sampleRateHz: Int): String {
    if (sampleRateHz <= 0) return "-- MHZ"
    val mhz = sampleRateHz / 1_000_000f
    val formatted = String.format(Locale.US, "%.1f", mhz).replace('.', ',')
    return "$formatted MHZ"
}

@Preview(name = "Hi-Res FLAC — 24-bit 96 kHz", showBackground = true, backgroundColor = 0xFF090B10)
@Composable
private fun AudioInfoRowHiResPreview() {
    AudiophileMusicPlayerTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            AudioInfoRow(
                audioFormat = AudioFormat(
                    sampleRateHz = 96_000,
                    bitDepth = 24,
                    channelCount = 2,
                    codec = AudioCodec.FLAC,
                    isLossless = true
                ),
                telemetryState = remember {
                    mutableStateOf(
                        AudioTelemetry(
                            streamInfo = OutputStreamInfo.Pcm(
                                codec = AudioCodec.FLAC,
                                sampleRateHz = 96_000,
                                bitDepth = 24,
                                bitrateKbps = 4_608
                            )
                        )
                    )
                },
                fallbackBitrateKbps = 4_608,
                onInfoClick = {}
            )
        }
    }
}

@Preview(name = "Lossy MP3 — 320 kbps", showBackground = true, backgroundColor = 0xFF090B10)
@Composable
private fun AudioInfoRowLossyPreview() {
    AudiophileMusicPlayerTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            AudioInfoRow(
                audioFormat = AudioFormat(
                    sampleRateHz = 44_100,
                    bitDepth = 16,
                    channelCount = 2,
                    codec = AudioCodec.MP3,
                    isLossless = false
                ),
                telemetryState = remember {
                    mutableStateOf(
                        AudioTelemetry(
                            streamInfo = OutputStreamInfo.Pcm(
                                codec = AudioCodec.MP3,
                                sampleRateHz = 44_100,
                                bitDepth = 16,
                                bitrateKbps = 320
                            )
                        )
                    )
                },
                fallbackBitrateKbps = 320,
                onInfoClick = {}
            )
        }
    }
}

@Preview(name = "Native DSD64", showBackground = true, backgroundColor = 0xFF090B10)
@Composable
private fun AudioInfoRowDsdPreview() {
    AudiophileMusicPlayerTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            AudioInfoRow(
                audioFormat = AudioFormat(
                    sampleRateHz = DsdRate.DSD64.sampleRateHz,
                    bitDepth = 1,
                    channelCount = 2,
                    codec = AudioCodec.DSD_64,
                    isLossless = true
                ),
                telemetryState = remember {
                    mutableStateOf(
                        AudioTelemetry(
                            streamInfo = OutputStreamInfo.Dsd(
                                codec = AudioCodec.DSD_64,
                                sourceContainer = "DSF",
                                sourceDsdRate = DsdRate.DSD64,
                                outputMode = DsdOutputMode.NativeDsd(maxRate = DsdRate.DSD64)
                            )
                        )
                    )
                },
                fallbackBitrateKbps = 0,
                onInfoClick = {}
            )
        }
    }
}

