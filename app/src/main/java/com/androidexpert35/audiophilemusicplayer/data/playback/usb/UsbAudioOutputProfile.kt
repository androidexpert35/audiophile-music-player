package com.androidexpert35.audiophilemusicplayer.data.playback.usb

/**
 * Concrete PCM format advertised or selected for direct USB audio output.
 *
 * @property sampleRateHz Output sample rate in Hz.
 * @property bitDepth Output PCM bit depth.
 * @property channelCount Interleaved PCM channel count.
 */
data class UsbAudioOutputProfile(
    val sampleRateHz: Int,
    val bitDepth: Int,
    val channelCount: Int = 2,
) {
    /** Byte width of one sample for one channel. */
    val bytesPerSample: Int
        get() = bitDepth / 8

    /** Byte width of one complete interleaved frame. */
    val bytesPerFrame: Int
        get() = bytesPerSample * channelCount
}

