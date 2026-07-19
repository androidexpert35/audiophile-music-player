package com.androidexpert35.audiophilemusicplayer.domain.model.playback

import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track

/**
 * Snapshot of the playback queue visible to the presentation layer.
 *
 * @property tracks Ordered list of tracks in the current queue.
 * @property currentIndex Zero-based index of the currently playing track, or `-1` if empty.
 * @property repeatMode Active repeat behaviour.
 * @property shuffleMode Active shuffle behaviour.
 */
data class QueueState(
    val tracks: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val shuffleMode: ShuffleMode = ShuffleMode.OFF
) {
    companion object {
        /** Default empty queue. */
        val EMPTY = QueueState()
    }
}

