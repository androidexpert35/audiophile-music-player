package com.androidexpert35.audiophilemusicplayer.data.playback

/**
 * Minimal PCM descriptor carrying the source file's decoded format parameters.
 *
 * Used as the bridge type between the FFmpeg decoder layer and
 * [AudioPathValidator]. The C++ JNI layer can push values here directly via
 * [AudioPathValidator.updateSourceFormat] without depending on the full
 * [com.androidexpert35.audiophilemusicplayer.data.playback.native_.AudioFormatInfo]
 * model, which contains engine-internal fields not relevant to path validation.
 *
 * @property sampleRateHz Decoded output sample rate in Hz (source-native rate,
 *   before any SoX or HAL resampling).
 * @property bitDepth Source PCM bit depth as reported by the codec
 *   (e.g. 16, 24, 32).
 * @property channelCount Number of interleaved PCM channels in the decoded
 *   output (1 = mono, 2 = stereo).
 */
data class AudioSourceFormat(
    val sampleRateHz: Int,
    val bitDepth: Int,
    val channelCount: Int,
)

