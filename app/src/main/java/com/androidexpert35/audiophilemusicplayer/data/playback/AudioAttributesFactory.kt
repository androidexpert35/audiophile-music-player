package com.androidexpert35.audiophilemusicplayer.data.playback

import android.media.AudioAttributes as AndroidAudioAttributes

/**
 * Factory producing Android [AudioAttributes][AndroidAudioAttributes] optimised
 * for audiophile-grade media playback.
 *
 * Configures `CONTENT_TYPE_MUSIC` + `USAGE_MEDIA` to signal the audio HAL
 * that this stream is high-fidelity music content. This allows the system to
 * route through the highest-quality signal path available (hardware DAC,
 * USB Audio Class 2, etc.) and avoid unnecessary resampling.
 */
object AudioAttributesFactory {

    /**
     * Builds [AndroidAudioAttributes] for maximum audio fidelity.
     *
     * @return Configured audio attributes targeting the hardware media path.
     */
    fun createMediaAttributes(): AndroidAudioAttributes =
        AndroidAudioAttributes.Builder()
            .setUsage(AndroidAudioAttributes.USAGE_MEDIA)
            .setContentType(AndroidAudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
}

