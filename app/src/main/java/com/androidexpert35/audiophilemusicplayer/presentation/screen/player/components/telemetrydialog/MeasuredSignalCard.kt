package com.androidexpert35.audiophilemusicplayer.presentation.screen.player.components.telemetrydialog

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.domain.model.analysis.StationaryAnalysis
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileMusicPlayerTheme
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileSecondary

/**
 * Reports what the current track's audio actually measured as, when it has been measured.
 *
 * Every other card in this sheet describes the live path — what the engine and the DAC
 * are doing to the signal right now. This one describes the source instead: values a
 * previous offline analysis pass stored for this audio, read from cache. It exists so
 * the measurements can be seen and judged before anything in the DSP chain is allowed
 * to act on them; nothing here influences playback.
 *
 * Absence is the normal case for most of a library, so it is stated plainly rather than
 * hidden: a track with no cached row says it has not been analysed, and an individual
 * statistic the measurement graph never produced is shown as absent rather than as a
 * plausible-looking zero.
 *
 * @param analysis Cached stationary measurements for the playing track, or `null` when
 *   that audio has not been analysed at the current schema version.
 */
@Composable
internal fun MeasuredSignalCard(analysis: StationaryAnalysis?) {
    TelemetrySection(
        icon = Icons.Outlined.Insights,
        title = stringResource(R.string.telemetry_section_measured_signal),
        accentColor = AudiophileSecondary,
    ) {
        if (analysis == null) {
            NotAnalysedBody()
        } else {
            MeasuredValues(analysis = analysis)
        }
    }
}

/**
 * Body shown when no measurement is cached for the playing audio.
 *
 * @see MeasuredSignalCard
 */
@Composable
private fun NotAnalysedBody() {
    TelemetryValueRow(
        label = stringResource(R.string.telemetry_measured_cutoff),
        value = stringResource(R.string.telemetry_measured_not_analysed),
    )
    Text(
        text = stringResource(R.string.telemetry_measured_not_analysed_explanation),
        style = MaterialTheme.typography.bodySmall,
        color = Color.White.copy(alpha = 0.48f),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Body listing the cached measurements for the playing audio.
 *
 * @param analysis Stationary measurements to display.
 */
@Composable
private fun MeasuredValues(analysis: StationaryAnalysis) {
    TelemetryValueRow(
        label = stringResource(R.string.telemetry_measured_cutoff),
        value = formatMeasuredFrequency(analysis.spectralRolloffHz),
    )
    TelemetryValueRow(
        label = stringResource(R.string.telemetry_measured_tilt),
        value = formatSpectralTilt(analysis.spectralSlope),
    )
    TelemetryValueRow(
        label = stringResource(R.string.telemetry_measured_correlation),
        value = formatCorrelation(analysis.interChannelCorrelation),
    )
    TelemetryValueRow(
        label = stringResource(R.string.telemetry_measured_stereo_width),
        value = formatStereoWidth(analysis.midRmsDbfs, analysis.sideRmsDbfs),
    )
    Text(
        text = stringResource(R.string.telemetry_measured_disclaimer),
        style = MaterialTheme.typography.bodySmall,
        color = Color.White.copy(alpha = 0.48f),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Preview(name = "Measured signal — analysed", showBackground = true, backgroundColor = 0xFF101014)
@Composable
private fun MeasuredSignalCardPreview() {
    AudiophileMusicPlayerTheme(darkTheme = true) {
        MeasuredSignalCard(
            analysis = StationaryAnalysis(
                spectralRolloffHz = 19_450.0,
                spectralCentroidHz = 2_380.0,
                spectralSlope = -0.72,
                noiseFloorDbfs = -96.4,
                dcOffset = 0.0001,
                leftRmsDbfs = -14.0,
                rightRmsDbfs = -14.2,
                midRmsDbfs = -13.8,
                sideRmsDbfs = -22.1,
                interChannelCorrelation = 0.93,
                windowCount = 4,
                frameCount = 176_400L,
            )
        )
    }
}

@Preview(name = "Measured signal — absent", showBackground = true, backgroundColor = 0xFF101014)
@Composable
private fun MeasuredSignalCardNotAnalysedPreview() {
    AudiophileMusicPlayerTheme(darkTheme = true) {
        MeasuredSignalCard(analysis = null)
    }
}
