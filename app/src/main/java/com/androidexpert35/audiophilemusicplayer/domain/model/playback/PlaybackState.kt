package com.androidexpert35.audiophilemusicplayer.domain.model.playback

import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track

/**
 * Snapshot of the current playback state exposed to the presentation layer.
 *
 * @property status Current discrete playback status.
 * @property currentTrack The track currently loaded in the player, or `null` if idle.
 * @property positionMs Current playback position in milliseconds.
 * @property durationMs Total duration of the current track in milliseconds.
 * @property playbackSpeed Current playback speed multiplier (1.0 = normal).
 */
data class PlaybackState(
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val currentTrack: Track? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playbackSpeed: Float = 1.0f
) {
    companion object {
        /** Default idle state when no media is loaded. */
        val IDLE = PlaybackState()
    }
}

