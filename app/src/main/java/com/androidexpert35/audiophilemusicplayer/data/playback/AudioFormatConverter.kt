package com.androidexpert35.audiophilemusicplayer.data.playback

import android.media.AudioFormat

/**
 * Pure-function helpers that convert source PCM parameters into Android
 * [AudioFormat] encoding constants and channel masks.
 *
 * These mappings are shared by the audio path validator, the USB bit-perfect
 * router, and any future data-layer component that needs to translate generic
 * PCM descriptors into platform API values without duplicating the same
 * bit-depth → encoding logic.
 *
 * All functions are intentionally free of side effects and Android framework
 * state so they can be unit-tested without a real device or emulator.
 */
internal object AudioFormatConverter {

    /**
     * Maps a source PCM bit depth to the closest [AudioFormat] encoding constant.
     *
     * | Bit-depth | Encoding |
     * |-----------|---------|
     * | 16 | [AudioFormat.ENCODING_PCM_16BIT] |
     * | 24 | [AudioFormat.ENCODING_PCM_24BIT_PACKED] |
     * | 32 | [AudioFormat.ENCODING_PCM_FLOAT] |
     *
     * 32-bit integer PCM is intentionally mapped to `ENCODING_PCM_FLOAT` because
     * Android's high-fidelity pipeline treats 32-bit sources as IEEE float — the
     * canonical representation for DSD-decimated and mastered high-res content.
     *
     * 24-bit packed (3 bytes/sample) is the canonical USB Audio Class 2 wire format.
     *
     * @param bitDepth Source bit depth.
     * @return Matching `AudioFormat.ENCODING_*` constant, or `null` for unsupported depths.
     */
    fun mapBitDepthToEncoding(bitDepth: Int): Int? = when (bitDepth) {
        16 -> AudioFormat.ENCODING_PCM_16BIT
        24 -> AudioFormat.ENCODING_PCM_24BIT_PACKED
        32 -> AudioFormat.ENCODING_PCM_FLOAT
        else -> null
    }

    /**
     * Maps a channel count to the corresponding `AudioFormat.CHANNEL_OUT_*` mask.
     *
     * @param channelCount Number of interleaved PCM channels.
     * @return Matching `CHANNEL_OUT_*` mask, or `null` for unsupported channel counts.
     */
    fun mapChannelCountToOutMask(channelCount: Int): Int? = when (channelCount) {
        1 -> AudioFormat.CHANNEL_OUT_MONO
        2 -> AudioFormat.CHANNEL_OUT_STEREO
        else -> null
    }
}

