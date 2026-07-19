package com.androidexpert35.audiophilemusicplayer.domain.model.playback

/**
 * Enumeration of discrete playback states exposed to the presentation layer.
 */
enum class PlaybackStatus {
    /** No media is loaded or the player has been released. */
    IDLE,
    /** Media is loaded but audio data is being buffered before playback can start. */
    BUFFERING,
    /** Audio is actively being rendered to the audio sink. */
    PLAYING,
    /** Playback is paused at the current position. */
    PAUSED,
    /** An unrecoverable playback error has occurred. */
    ERROR
}

