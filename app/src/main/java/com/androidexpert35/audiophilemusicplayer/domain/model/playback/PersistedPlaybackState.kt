package com.androidexpert35.audiophilemusicplayer.domain.model.playback

/**
 * Immutable domain snapshot of the last-saved playback session.
 *
 * Carries only the minimal data required to fully re-hydrate the ExoPlayer queue and
 * restore the playback cursor on the next app launch. Full track metadata is never
 * duplicated here — it is fetched on demand from the Room-indexed library using
 * [queueTrackIds].
 *
 * @property currentTrackId MediaStore identifier of the track that was last active.
 * @property playbackPositionMs Playback cursor position in milliseconds at save time.
 * @property currentQueueIndex Zero-based index of [currentTrackId] in [queueTrackIds].
 * @property queueTrackIds Ordered list of track IDs forming the saved queue.
 * @property repeatMode Active repeat behaviour persisted with the session.
 * @property shuffleMode Active shuffle behaviour persisted with the session.
 */
data class PersistedPlaybackState(
    val currentTrackId: Long,
    val playbackPositionMs: Long,
    val currentQueueIndex: Int,
    val queueTrackIds: List<Long>,
    val repeatMode: RepeatMode,
    val shuffleMode: ShuffleMode
)

