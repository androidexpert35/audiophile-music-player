package com.androidexpert35.audiophilemusicplayer.data.playback.focus

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

/** Owns audio focus only while playback is actively producing audio. */
@Singleton
internal class AudioFocusController @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    /**
     * Requests media focus and emits every ownership change until collection ends.
     *
     * Cancelling collection abandons the exact [AudioFocusRequest] that was
     * granted, preventing stale listeners from surviving pause or service
     * teardown.
     *
     * @return Focus lifecycle states for one active playback interval.
     */
    fun observeActiveFocus(): Flow<AudioFocusState> = callbackFlow {
        trySend(AudioFocusState.REQUESTING)

        val manager = audioManager
        if (manager == null) {
            trySend(AudioFocusState.DENIED)
            close()
            return@callbackFlow
        }

        val listener = AudioManager.OnAudioFocusChangeListener { focusChange ->
            trySend(AudioFocusChangePolicy.resolve(focusChange))
        }
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(listener)
            .build()

        val requestResult = manager.requestAudioFocus(request)
        trySend(
            if (requestResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                AudioFocusState.GRANTED
            } else {
                AudioFocusState.DENIED
            }
        )

        awaitClose {
            manager.abandonAudioFocusRequest(request)
        }
    }.distinctUntilChanged()
}
