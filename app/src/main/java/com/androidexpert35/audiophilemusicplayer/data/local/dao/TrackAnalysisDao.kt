package com.androidexpert35.audiophilemusicplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.androidexpert35.audiophilemusicplayer.data.local.entity.TrackAnalysisEntity

/**
 * Room DAO for the per-audio measured-analysis cache.
 *
 * Rows are addressed by content key, and every read is scoped to a schema version
 * so measurements taken under an older interpretation are simply not returned.
 *
 * The DAO deliberately exposes whole-row reads and writes rather than per-class
 * `UPDATE` statements: merging a new class into an existing row is a decision
 * about *meaning* (an older schema version must be discarded, not merged) and it
 * belongs in the mapper where it can be unit-tested without a database.
 */
@Dao
interface TrackAnalysisDao {

    /**
     * Reads the cached analysis for one piece of audio, whatever version wrote it.
     *
     * @param audioKey Content key of the audio to look up.
     * @return The stored row, or `null` when the audio has never been analysed.
     */
    @Query("SELECT * FROM track_analysis WHERE audioKey = :audioKey LIMIT 1")
    suspend fun getByAudioKey(audioKey: String): TrackAnalysisEntity?

    /**
     * Inserts or replaces a whole analysis row.
     *
     * @param entity Complete row to persist, including the columns of any class
     *   carried over from the previous row.
     */
    @Upsert
    suspend fun upsert(entity: TrackAnalysisEntity)

    /**
     * Counts rows whose stationary measurements are missing or stale.
     *
     * A row written under a superseded schema version counts as missing, which is
     * what makes a version bump equivalent to "recompute everything".
     *
     * @param schemaVersion Currently valid measurement schema version.
     * @return Number of cached rows still needing a Class S pass.
     */
    @Query(
        """
        SELECT COUNT(*) FROM track_analysis
        WHERE stationaryAnalysedAtEpochSeconds IS NULL OR schemaVersion != :schemaVersion
        """
    )
    suspend fun countMissingStationary(schemaVersion: Int): Int

    /**
     * Counts rows whose integral measurements are missing or stale.
     *
     * @param schemaVersion Currently valid measurement schema version.
     * @return Number of cached rows still needing a Class I pass.
     */
    @Query(
        """
        SELECT COUNT(*) FROM track_analysis
        WHERE integralAnalysedAtEpochSeconds IS NULL OR schemaVersion != :schemaVersion
        """
    )
    suspend fun countMissingIntegral(schemaVersion: Int): Int
}
