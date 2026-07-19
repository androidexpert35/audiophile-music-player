package com.androidexpert35.audiophilemusicplayer.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity persisting a single liked-track entry.
 *
 * Using [trackId] as the primary key ensures the table contains at most one
 * row per track; a second like attempt is silently ignored by the DAO's
 * [androidx.room.OnConflictStrategy.IGNORE] policy.
 *
 * @property trackId Stable MediaStore identifier of the liked track.
 * @property likedAt Epoch milliseconds when the track was liked.
 */
@Entity(tableName = "liked_songs")
data class LikedSongEntity(
    @PrimaryKey val trackId: Long,
    val likedAt: Long
)

