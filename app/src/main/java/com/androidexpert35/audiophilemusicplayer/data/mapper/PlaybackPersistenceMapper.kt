package com.androidexpert35.audiophilemusicplayer.data.mapper

import com.androidexpert35.audiophilemusicplayer.data.local.entity.PlaybackStateEntity
import com.androidexpert35.audiophilemusicplayer.domain.model.playback.PersistedPlaybackState
import com.androidexpert35.audiophilemusicplayer.domain.model.playback.RepeatMode
import com.androidexpert35.audiophilemusicplayer.domain.model.playback.ShuffleMode

/**
 * Maps between the [PlaybackStateEntity] Room row and the [PersistedPlaybackState] domain model.
 *
 * Enum names are stored as plain strings so the schema remains human-readable and
 * resilient to future enum ordering changes. Invalid stored values fall back to safe
 * defaults ([RepeatMode.OFF] / [ShuffleMode.OFF]) rather than throwing.
 */

/**
 * Converts a [PlaybackStateEntity] database row to the domain [PersistedPlaybackState].
 *
 * @return Domain playback state snapshot with enums parsed from their stored names.
 */
fun PlaybackStateEntity.toDomain(): PersistedPlaybackState = PersistedPlaybackState(
    currentTrackId = currentTrackId,
    playbackPositionMs = playbackPositionMs,
    currentQueueIndex = currentQueueIndex,
    queueTrackIds = queueTrackIds,
    repeatMode = runCatching { RepeatMode.valueOf(repeatMode) }.getOrDefault(RepeatMode.OFF),
    shuffleMode = runCatching { ShuffleMode.valueOf(shuffleMode) }.getOrDefault(ShuffleMode.OFF)
)

/**
 * Converts a [PersistedPlaybackState] domain model to a [PlaybackStateEntity] for storage.
 *
 * @return Room entity ready for insertion into the `playback_state` table.
 */
fun PersistedPlaybackState.toEntity(): PlaybackStateEntity = PlaybackStateEntity(
    currentTrackId = currentTrackId,
    playbackPositionMs = playbackPositionMs,
    currentQueueIndex = currentQueueIndex,
    queueTrackIds = queueTrackIds,
    repeatMode = repeatMode.name,
    shuffleMode = shuffleMode.name
)

