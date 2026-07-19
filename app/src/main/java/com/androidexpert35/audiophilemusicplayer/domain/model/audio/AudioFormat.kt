package com.androidexpert35.audiophilemusicplayer.domain.model.audio

/**
 * Describes the encoded audio format of a media file on disk.
 *
 * This is the *file-level* format — contrast with [AudioTelemetry] which
 * captures the *output-level* format actually reaching the DAC hardware.
 *
 * @property sampleRateHz Sample rate in Hertz (e.g., 44_100, 96_000, 192_000).
 * @property bitDepth Bits per sample (e.g., 16, 24, 32).
 * @property channelCount Number of audio channels (typically 2 for stereo).
 * @property codec The codec used to encode the audio data.
 * @property isLossless Whether the file preserves the original signal without data loss.
 */
data class AudioFormat(
    val sampleRateHz: Int,
    val bitDepth: Int,
    val channelCount: Int,
    val codec: AudioCodec,
    val isLossless: Boolean
) {
    companion object {
        /** Fallback format when metadata extraction fails. */
        val UNKNOWN = AudioFormat(
            sampleRateHz = 0,
            bitDepth = 0,
            channelCount = 0,
            codec = AudioCodec.UNKNOWN,
            isLossless = false
        )
    }
}

