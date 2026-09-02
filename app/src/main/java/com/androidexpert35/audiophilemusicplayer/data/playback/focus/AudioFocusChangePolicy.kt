package com.androidexpert35.audiophilemusicplayer.data.playback.focus

import android.media.AudioManager

/** Maps Android focus callbacks to the conservative policy required by bit-perfect playback. */
internal object AudioFocusChangePolicy {

    /**
     * Converts a platform focus callback into app-owned focus state.
     *
     * Ducking is treated as loss because applying a gain reduction would alter
     * the bit-perfect signal, while ignoring it would keep the DAC unavailable
     * to the application that requested focus.
     */
    fun resolve(focusChange: Int): AudioFocusState = when (focusChange) {
        AudioManager.AUDIOFOCUS_GAIN -> AudioFocusState.GRANTED
        AudioManager.AUDIOFOCUS_LOSS,
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> AudioFocusState.LOST
        else -> AudioFocusState.LOST
    }
}
