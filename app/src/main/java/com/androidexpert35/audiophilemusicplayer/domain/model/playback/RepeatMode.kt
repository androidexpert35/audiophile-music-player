package com.androidexpert35.audiophilemusicplayer.domain.model.playback

/**
 * Repeat behaviour for the playback queue.
 */
enum class RepeatMode {
    /** No repeat — playback stops after the last track. */
    OFF,
    /** Repeat the current track indefinitely. */
    ONE,
    /** Loop the entire queue from the beginning after the last track. */
    ALL
}

