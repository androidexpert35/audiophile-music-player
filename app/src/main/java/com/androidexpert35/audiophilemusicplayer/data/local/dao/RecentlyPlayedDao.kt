package com.androidexpert35.audiophilemusicplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Stores playback recency and personal play counts for local tracks.
 *
 * Each track appears at most once. A playback start atomically increments its
 * counter and refreshes its timestamp, invalidating both recency and popularity
 * streams in one database write.
 */
@Dao
interface RecentlyPlayedDao {

    /**
     * Returns a live stream of recently-played track IDs, most-recent first.
     *
     * Room re-emits whenever a row in `recently_played` is inserted or updated.
     *
     * @param limit Maximum number of rows to include in each emission.
     * @return [Flow] emitting an ordered [List] of track IDs on every table change.
     */
    @Query("SELECT trackId FROM recently_played ORDER BY playedAt DESC LIMIT :limit")
    fun observeRecentlyPlayedTrackIds(limit: Int): Flow<List<Long>>

    /**
     * Returns the most-played IDs among a caller-provided track collection.
     *
     * Count is the primary ordering key. The latest playback timestamp resolves
     * equal counts, while the stable ID makes the final ordering deterministic.
     * Tracks with no recorded playback are intentionally absent.
     *
     * @param trackIds Stable MediaStore identifiers eligible for the ranking.
     * @param limit Maximum number of ranked IDs to include in each emission.
     * @return [Flow] emitting the ordered IDs whenever playback history changes.
     */
    @Query(
        """
        SELECT trackId
        FROM recently_played
        WHERE trackId IN (:trackIds)
        ORDER BY playCount DESC, playedAt DESC, trackId ASC
        LIMIT :limit
        """
    )
    fun observeMostPlayedTrackIds(trackIds: List<Long>, limit: Int): Flow<List<Long>>

    /**
     * Atomically records one playback start for a track.
     *
     * SQLite's conflict-update form prevents lost increments if two playback
     * events arrive close together. New rows begin with one recorded play.
     *
     * @param trackId Stable MediaStore identifier of the track that began playing.
     * @param playedAt Epoch milliseconds for the playback start.
     */
    @Query(
        """
        INSERT INTO recently_played (trackId, playedAt, playCount)
        VALUES (:trackId, :playedAt, 1)
        ON CONFLICT(trackId) DO UPDATE SET
            playedAt = excluded.playedAt,
            playCount = recently_played.playCount + 1
        """
    )
    suspend fun recordPlaybackStart(trackId: Long, playedAt: Long)
}
