package com.androidexpert35.audiophilemusicplayer.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Persists playback recency and total starts for a single local track.
 *
 * Using [trackId] as the primary key guarantees each track appears at most once
 * in the history. When the same track starts again the DAO atomically updates
 * [playedAt] and increments [playCount], supporting both recency and personal
 * most-played rankings from one compact row.
 *
 * @property trackId Stable MediaStore identifier of the played track.
 * @property playedAt Epoch milliseconds when playback of this track began.
 * @property playCount Number of distinct playback starts recorded for this track.
 */
@Entity(tableName = "recently_played")
data class RecentlyPlayedEntity(
    @PrimaryKey val trackId: Long,
    val playedAt: Long,
    val playCount: Long
)
