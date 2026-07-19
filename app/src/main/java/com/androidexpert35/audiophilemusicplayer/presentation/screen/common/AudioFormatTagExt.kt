package com.androidexpert35.audiophilemusicplayer.presentation.screen.common

import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioCodec
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioFormat

/**
 * Shared presentation-layer helpers for building compact audio-quality tag strings.
 *
 * These functions are consumed by both [MiniPlayerBar] (track-level) and
 * [AlbumDetailHeroHeader] (album-level aggregate) to guarantee identical labels
 * and colours across every surface that displays audio quality information.
 */

/**
 * Builds a compact codec-only quality label from a track's file-level [AudioFormat].
 *
 * Returns `null` when no recognised codec is available, in which case no pill
 * should be rendered. Bit depth is intentionally excluded — use [buildResolutionLabel]
 * on surfaces that need the full technical breakdown (e.g. album hero header).
 *
 * Examples: `"FLAC"`, `"MP3"`, `"AAC"`.
 *
 * @param audioFormat File-level format to derive the label from.
 * @return Codec display name, or `null` if the codec is unknown.
 */
fun buildQualityLabel(audioFormat: AudioFormat): String? {
    val codec = audioFormat.codec
    if (codec == AudioCodec.UNKNOWN) return null
    return codec.displayName.trim().takeIf { it.isNotEmpty() }
}


/**
 * Builds a combined resolution label from bit depth and sample rate for use on
 * the album detail hero header.
 *
 * Shows both values separated by " / " when both are available, or either value
 * alone when only one is present. Returns `null` when neither is available.
 *
 * Examples: `"24-bit / 96 kHz"`, `"24-bit / 44.1 kHz"`, `"24-bit"`, `"96 kHz"`.
 *
 * @param bitDepth Bits per sample (0 = unknown).
 * @param sampleRateHz Sample rate in Hertz (0 = unknown).
 * @return Combined label string, or `null` if no data is available.
 */
fun buildResolutionLabel(bitDepth: Int, sampleRateHz: Int): String? {
    val bitPart = if (bitDepth > 0) "${bitDepth}-bit" else null
    val ratePart = formatSampleRateLabel(sampleRateHz)
    return when {
        bitPart != null && ratePart != null -> "$bitPart / $ratePart"
        bitPart != null -> bitPart
        ratePart != null -> ratePart
        else -> null
    }
}
 /**
 * Shows one decimal place only when it is non-zero (e.g. 44.1 kHz vs 96 kHz).
 * Returns `null` when [sampleRateHz] is zero or negative.
 *
 * @param sampleRateHz Sample rate in Hertz.
 * @return Formatted string such as `"44.1 kHz"` or `"96 kHz"`, or `null`.
 */
fun formatSampleRateLabel(sampleRateHz: Int): String? {
    if (sampleRateHz <= 0) return null
    val kHz = sampleRateHz / 1_000f
    return if (kHz % 1f == 0f) "${kHz.toInt()} kHz" else "$kHz kHz"
}

