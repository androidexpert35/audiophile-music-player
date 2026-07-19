package com.androidexpert35.audiophilemusicplayer.domain.model.audio

/**
 * Concrete USB DAC PCM format advertised by the connected device.
 *
 * This model is used by the Settings UI and preference layer so the app can
 * expose the DAC's real advertised capabilities instead of collapsing them into
 * a small fixed preset list.
 *
 * @property sampleRateHz Output sample rate in Hz.
 * @property bitDepth Output PCM bit depth.
 * @property channelCount Interleaved PCM channel count.
 */
data class UsbAudioFormat(
    val sampleRateHz: Int,
    val bitDepth: Int,
    val channelCount: Int = 2,
)

