package com.androidexpert35.audiophilemusicplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.androidexpert35.audiophilemusicplayer.data.local.entity.LikedSongEntity
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for the liked-songs table.
 *
 * All observation queries return [Flow] so Room automatically invalidates
 * and re-emits whenever the underlying table changes, giving the UI a live
 * reactive view of the user's liked tracks without polling.
 */
@Dao
interface LikedSongDao {

    /**
     * Returns a live stream of all liked track IDs.
     *
     * Room re-emits whenever the `liked_songs` table is modified (like, unlike).
     *
     * @return [Flow] emitting the full [List] of liked track IDs on every table change.
     */
    @Query("SELECT trackId FROM liked_songs")
    fun observeLikedSongIds(): Flow<List<Long>>

    /**
     * One-shot read of all currently liked track IDs.
     *
     * @return List of all liked track IDs at the moment of the call.
     */
    @Query("SELECT trackId FROM liked_songs")
    suspend fun getLikedSongIds(): List<Long>

    /**
     * Checks whether the given track is currently liked.
     *
     * @param trackId The track to check.
     * @return `true` if a row exists for [trackId], `false` otherwise.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM liked_songs WHERE trackId = :trackId)")
    suspend fun isLiked(trackId: Long): Boolean

    /**
     * Inserts a liked-song row, ignoring the write if the track is already liked.
     *
     * @param entity The like entry to persist.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun likeSong(entity: LikedSongEntity)

    /**
     * Removes the liked entry for the specified track.
     *
     * @param trackId Track to unlike.
     */
    @Query("DELETE FROM liked_songs WHERE trackId = :trackId")
    suspend fun unlikeSong(trackId: Long)
}

