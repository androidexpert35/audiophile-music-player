package com.androidexpert35.audiophilemusicplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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
    @Query("SELECT trackId FROM liked_songs ORDER BY likedAt ASC")
    fun observeLikedSongIds(): Flow<List<Long>>

    /**
     * One-shot read of all currently liked track IDs.
     *
     * @return List of all liked track IDs at the moment of the call.
     */
    @Query("SELECT trackId FROM liked_songs ORDER BY likedAt ASC")
    suspend fun getLikedSongIds(): List<Long>

    /**
     * Reads the complete liked-song collection in playlist order.
     *
     * @return Persisted liked rows ordered from earliest to latest addition.
     */
    @Query("SELECT * FROM liked_songs ORDER BY likedAt ASC")
    suspend fun getLikedSongs(): List<LikedSongEntity>

    /** Removes every liked-song row before an ordered collection replacement. */
    @Query("DELETE FROM liked_songs")
    suspend fun clearLikedSongs()

    /**
     * Inserts an ordered liked-song snapshot after the previous collection is cleared.
     *
     * @param entities Complete replacement rows in desired playlist order.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLikedSongs(entities: List<LikedSongEntity>)

    /**
     * Atomically replaces liked membership and ordering to mirror the favorites M3U.
     *
     * @param entities Complete liked-song snapshot in playlist order.
     */
    @Transaction
    suspend fun replaceLikedSongs(entities: List<LikedSongEntity>) {
        clearLikedSongs()
        insertLikedSongs(entities)
    }
}
