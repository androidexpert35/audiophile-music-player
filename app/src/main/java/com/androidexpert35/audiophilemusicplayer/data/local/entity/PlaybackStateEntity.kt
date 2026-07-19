package com.androidexpert35.audiophilemusicplayer.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.androidexpert35.audiophilemusicplayer.data.local.entity.PlaybackStateEntity.Companion.SINGLETON_ID

/**
 * Single-row Room entity persisting the last-known playback session.
 *
 * Only a single row (keyed by [SINGLETON_ID]) is ever written; subsequent saves
 * use [androidx.room.OnConflictStrategy.REPLACE] to overwrite it. This approach
 * keeps storage overhead negligible — the queue is encoded as a comma-separated
 * list of track IDs rather than duplicating full metadata already in `tracks`.
 *
 * @property id Fixed primary key identifying the singleton row.
 * @property currentTrackId MediaStore identifier of the last-active track.
 * @property playbackPositionMs Playback cursor position in milliseconds when the session was saved.
 * @property currentQueueIndex Zero-based index of [currentTrackId] within the saved queue.
 * @property queueTrackIds Ordered list of track IDs forming the saved queue, serialised by
 *   [com.androidexpert35.audiophilemusicplayer.data.local.converter.LongListTypeConverter].
 * @property repeatMode Name of the [com.androidexpert35.audiophilemusicplayer.domain.model.playback.RepeatMode]
 *   enum constant active when the session was saved.
 * @property shuffleMode Name of the [com.androidexpert35.audiophilemusicplayer.domain.model.playback.ShuffleMode]
 *   enum constant active when the session was saved.
 */
@Entity(tableName = "playback_state")
data class PlaybackStateEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val currentTrackId: Long,
    val playbackPositionMs: Long,
    val currentQueueIndex: Int,
    val queueTrackIds: List<Long>,
    val repeatMode: String,
    val shuffleMode: String
) {
    companion object {
        /** Fixed primary key — only one session row exists at any time. */
        const val SINGLETON_ID: Int = 1
    }
}

