package com.androidexpert35.audiophilemusicplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.androidexpert35.audiophilemusicplayer.data.local.entity.PlaybackStateEntity

/**
 * Room DAO for reading and writing the singleton playback-session persistence row.
 *
 * At most one row exists in the `playback_state` table. All writes use
 * [OnConflictStrategy.REPLACE] so new state silently supersedes the previous snapshot.
 */
@Dao
interface PlaybackStateDao {

    /**
     * Reads the persisted playback session, or `null` if no session has been saved yet.
     *
     * @return The singleton [PlaybackStateEntity] row, or `null` on first launch.
     */
    @Query("SELECT * FROM playback_state WHERE id = :id LIMIT 1")
    suspend fun getPlaybackState(id: Int = PlaybackStateEntity.SINGLETON_ID): PlaybackStateEntity?

    /**
     * Inserts or replaces the playback session snapshot.
     *
     * Using [OnConflictStrategy.REPLACE] on the fixed primary key guarantees that only
     * one row ever exists, automatically overwriting the previous session.
     *
     * @param entity Current session state to persist.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlaybackState(entity: PlaybackStateEntity)
}

