package com.androidexpert35.audiophilemusicplayer.presentation.screen.player.components.telemetrydialog

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.DsdOutputMode
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.DsdRate
import java.util.Locale

/** Formats a sample rate in Hz into a compact kHz display string. */
internal fun formatSampleRate(sampleRateHz: Int): String {
    if (sampleRateHz <= 0) return "—"
    val khz = sampleRateHz / 1000f
    return if (khz == khz.toLong().toFloat()) {
        "${khz.toLong()} kHz"
    } else {
        "${String.format(Locale.US, "%.1f", khz)} kHz"
    }
}

/** Formats bit depth with a unit suffix. */
internal fun formatBitDepth(bitDepth: Int): String =
    if (bitDepth > 0) "$bitDepth-bit" else "—"

/** Formats bitrate with a unit suffix. */
internal fun formatBitrate(bitrateKbps: Int): String =
    if (bitrateKbps > 0) "$bitrateKbps kbps" else "—"

/** Formats a channel count into a listener-friendly layout label. */
internal fun formatChannelCount(channelCount: Int): String = when (channelCount) {
    1 -> "Mono"
    2 -> "Stereo"
    in 3..Int.MAX_VALUE -> "$channelCount ch"
    else -> "—"
}

/** Formats a PCM shape as `bit-depth / sample-rate` for compact pipeline cards. */
internal fun formatPcmShape(bitDepth: Int, sampleRateHz: Int): String {
    val depth = formatBitDepth(bitDepth)
    val rate = formatSampleRate(sampleRateHz)
    return when {
        depth == "—" && rate == "—" -> "—"
        depth == "—" -> rate
        rate == "—" -> depth
        else -> "$depth / $rate"
    }
}

/** Formats a DSD family label. */
internal fun formatDsdRate(rate: DsdRate?): String = rate?.displayName ?: "—"

/** Formats the currently negotiated DSD output transport. */
@Composable
internal fun formatDsdOutputMode(mode: DsdOutputMode?, isResampled: Boolean = false): String = when {
    isResampled -> stringResource(R.string.telemetry_path_status_resampled)
    mode is DsdOutputMode.NativeDsd -> stringResource(R.string.telemetry_resampler_native)
    mode is DsdOutputMode.DoP -> "DoP"
    else -> "—"
}

/**
 * Formats the carrier PCM shape used for DoP or resampled-DSD transport.
 *
 * Returns a compact `"<bit-depth>-bit / <sample-rate>"` label, or `"—"` when
 * the inputs aren't meaningful (e.g. native-DSD sessions that have no PCM
 * carrier to describe).
 *
 * @param bitDepth Bit depth of the output PCM carrier (typically 24).
 * @param sampleRateHz Sample rate of the output PCM carrier in Hertz.
 */
internal fun formatDopCarrier(bitDepth: Int, sampleRateHz: Int): String {
    if (bitDepth <= 0 || sampleRateHz <= 0) return "—"
    return "${bitDepth}-bit / ${formatSampleRate(sampleRateHz)}"
}

/**
 * Formats a measured frequency for the signal read-out, in kHz.
 *
 * A statistic the measurement graph never produced arrives as `null` and is shown as
 * absent rather than as a plausible-looking zero — "the pass could not measure this"
 * and "this source really has no energy up there" are different claims.
 *
 * @param hz Measured frequency in Hertz, or `null` when the value was not produced.
 */
internal fun formatMeasuredFrequency(hz: Double?): String {
    if (hz == null || hz <= 0.0) return ABSENT_MEASUREMENT
    return "${String.format(Locale.US, "%.1f", hz / 1000.0)} kHz"
}

/**
 * Formats the measured spectral tilt.
 *
 * Kept as the raw signed slope rather than a "bright / dark" verdict: this ticket
 * surfaces the numbers so they can be judged, and a label would quietly do the judging.
 *
 * @param slope Measured spectral slope, or `null` when the value was not produced.
 */
internal fun formatSpectralTilt(slope: Double?): String =
    slope?.let { String.format(Locale.US, "%+.2f", it) } ?: ABSENT_MEASUREMENT

/**
 * Formats an inter-channel correlation in `[-1, 1]`.
 *
 * @param correlation Measured correlation, or `null` when a channel was digitally
 *   silent and the ratio had no meaning.
 */
internal fun formatCorrelation(correlation: Double?): String =
    correlation?.let { String.format(Locale.US, "%+.2f", it) } ?: ABSENT_MEASUREMENT

/**
 * Formats the measured stereo width as the mid-to-side energy distance in dB.
 *
 * The distance is the meaningful figure: a large one is a narrow, nearly mono image
 * and a small one a wide image, whereas either RMS on its own only says how loud the
 * measured windows were.
 *
 * @param midRmsDbfs Mid-signal RMS in dBFS, or `null` when not produced.
 * @param sideRmsDbfs Side-signal RMS in dBFS, or `null` when not produced.
 */
internal fun formatStereoWidth(midRmsDbfs: Double?, sideRmsDbfs: Double?): String {
    if (midRmsDbfs == null || sideRmsDbfs == null) return ABSENT_MEASUREMENT
    return "${String.format(Locale.US, "%.1f", midRmsDbfs - sideRmsDbfs)} dB"
}

/** Shown in place of a measurement the analysis pass did not produce. */
private const val ABSENT_MEASUREMENT = "—"
