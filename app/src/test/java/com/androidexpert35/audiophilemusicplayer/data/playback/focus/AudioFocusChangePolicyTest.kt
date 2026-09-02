package com.androidexpert35.audiophilemusicplayer.data.playback.focus

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Test

/** Verifies the focus policy used to protect exclusive USB ownership. */
class AudioFocusChangePolicyTest {

    @Test
    fun `given focus gain when resolved then playback may continue`() {
        assertEquals(
            AudioFocusState.GRANTED,
            AudioFocusChangePolicy.resolve(AudioManager.AUDIOFOCUS_GAIN),
        )
    }

    @Test
    fun `given any focus loss when resolved then output must be released`() {
        val losses = listOf(
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
        )

        losses.forEach { focusChange ->
            assertEquals(
                AudioFocusState.LOST,
                AudioFocusChangePolicy.resolve(focusChange),
            )
        }
    }
}
