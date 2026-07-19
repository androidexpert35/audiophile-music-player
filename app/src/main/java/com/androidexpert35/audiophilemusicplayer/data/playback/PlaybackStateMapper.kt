package com.androidexpert35.audiophilemusicplayer.data.playback

import androidx.media3.common.Player
import com.androidexpert35.audiophilemusicplayer.domain.model.playback.PlaybackStatus
import com.androidexpert35.audiophilemusicplayer.domain.model.playback.RepeatMode
import com.androidexpert35.audiophilemusicplayer.domain.model.playback.ShuffleMode

/**
 * Maps Media3 [Player] state constants to domain playback models.
 *
 * Keeps all framework-to-domain conversion logic in one place so that
 * neither the repository nor the ViewModel depends on Media3 types.
 */
object PlaybackStateMapper {

    /**
     * Converts a Media3 playback state + isPlaying flag to a domain [PlaybackStatus].
     *
     * @param playerState One of [Player.STATE_IDLE], [Player.STATE_BUFFERING],
     *                    [Player.STATE_READY], [Player.STATE_ENDED].
     * @param isPlaying Whether the player is actively rendering audio.
     * @return The corresponding [PlaybackStatus].
     */
    fun toPlaybackStatus(playerState: Int, isPlaying: Boolean): PlaybackStatus = when {
        playerState == Player.STATE_IDLE -> PlaybackStatus.IDLE
        playerState == Player.STATE_ENDED -> PlaybackStatus.IDLE
        playerState == Player.STATE_BUFFERING -> PlaybackStatus.BUFFERING
        isPlaying -> PlaybackStatus.PLAYING
        else -> PlaybackStatus.PAUSED
    }

    /**
     * Converts a domain [RepeatMode] to the Media3 [Player] repeat mode constant.
     *
     * @param mode Domain repeat mode.
     * @return Media3 repeat mode constant.
     */
    fun toMedia3RepeatMode(mode: RepeatMode): Int = when (mode) {
        RepeatMode.OFF -> Player.REPEAT_MODE_OFF
        RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        RepeatMode.ALL -> Player.REPEAT_MODE_ALL
    }

    /**
     * Converts a Media3 repeat mode constant to a domain [RepeatMode].
     *
     * @param media3Mode Media3 repeat mode constant.
     * @return Domain [RepeatMode].
     */
    fun fromMedia3RepeatMode(media3Mode: Int): RepeatMode = when (media3Mode) {
        Player.REPEAT_MODE_ONE -> RepeatMode.ONE
        Player.REPEAT_MODE_ALL -> RepeatMode.ALL
        else -> RepeatMode.OFF
    }

    /**
     * Converts a domain [ShuffleMode] to the Media3 shuffle enabled flag.
     *
     * @param mode Domain shuffle mode.
     * @return `true` if shuffle is on, `false` otherwise.
     */
    fun toMedia3ShuffleEnabled(mode: ShuffleMode): Boolean =
        mode == ShuffleMode.ON

    /**
     * Converts a Media3 shuffle enabled flag to a domain [ShuffleMode].
     *
     * @param enabled Whether shuffle is enabled in Media3.
     * @return Domain [ShuffleMode].
     */
    fun fromMedia3ShuffleEnabled(enabled: Boolean): ShuffleMode =
        if (enabled) ShuffleMode.ON else ShuffleMode.OFF
}

