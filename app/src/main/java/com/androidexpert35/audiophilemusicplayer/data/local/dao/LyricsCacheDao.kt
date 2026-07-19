package com.androidexpert35.audiophilemusicplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.androidexpert35.audiophilemusicplayer.data.local.entity.LyricsCacheEntity

/**
 * Room DAO for reading and writing the lyrics cache table.
 *
 * Lyrics are keyed by a stable string derived from track metadata
 * rather than a MediaStore ID to survive library re-indexing.
 */
@Dao
interface LyricsCacheDao {

    /**
     * Looks up a cached lyrics entry by its [cacheKey].
     *
     * @param cacheKey Composite key derived from track title, artist, album, and duration.
     * @return The cached [LyricsCacheEntity], or `null` on a cache miss.
     */
    @Query("SELECT * FROM lyrics_cache WHERE cacheKey = :cacheKey LIMIT 1")
    suspend fun getByKey(cacheKey: String): LyricsCacheEntity?

    /**
     * Inserts or replaces a lyrics cache entry.
     *
     * Uses `REPLACE` conflict strategy so stale rows are transparently overwritten
     * if the API later returns updated lyrics for the same key.
     *
     * @param entity The [LyricsCacheEntity] to persist.
     */
    @Upsert
    suspend fun upsert(entity: LyricsCacheEntity)
}

