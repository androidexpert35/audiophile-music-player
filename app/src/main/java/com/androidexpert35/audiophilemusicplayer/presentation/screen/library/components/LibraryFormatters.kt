package com.androidexpert35.audiophilemusicplayer.presentation.screen.library.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.androidexpert35.audiophilemusicplayer.R
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow

/**
 * Shared formatting helpers for library and album-overview presentation.
 */
internal fun formatTrackDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1_000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L

    return if (hours > 0) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }
}

/**
 * Formats a byte count into a compact IEC-style string for album metadata.
 */
@Composable
internal fun formatByteCount(bytes: Long): String {
    val byteUnit = stringResource(R.string.unit_byte_abbreviation)
    if (bytes <= 0L) return stringResource(R.string.format_file_size_bytes, 0L, byteUnit)
    if (bytes < 1024L) return stringResource(R.string.format_file_size_bytes, bytes, byteUnit)

    val units = listOf(
        stringResource(R.string.unit_kilobyte_abbreviation),
        stringResource(R.string.unit_megabyte_abbreviation),
        stringResource(R.string.unit_gigabyte_abbreviation),
        stringResource(R.string.unit_terabyte_abbreviation)
    )
    val digitGroup = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceAtMost(units.size)
    val scaled = bytes / 1024.0.pow(digitGroup.toDouble())
    return stringResource(R.string.format_file_size_scaled, scaled, units[digitGroup - 1])
}

/**
 * Formats a sample-rate and bit-depth pair for album quality summaries.
 */
@Composable
internal fun formatQualityLabel(sampleRateHz: Int, bitDepth: Int): String = when {
    sampleRateHz > 0 && bitDepth > 0 -> {
        stringResource(
            R.string.format_quality_sample_rate_bit_depth,
            sampleRateHz / 1000f,
            bitDepth
        )
    }
    sampleRateHz > 0 -> stringResource(R.string.format_quality_sample_rate, sampleRateHz / 1000f)
    bitDepth > 0 -> stringResource(R.string.format_quality_bit_depth, bitDepth)
    else -> stringResource(R.string.common_unknown)
}

