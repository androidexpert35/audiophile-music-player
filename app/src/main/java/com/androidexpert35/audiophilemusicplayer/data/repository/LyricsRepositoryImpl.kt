package com.androidexpert35.audiophilemusicplayer.data.repository

import com.androidexpert35.audiophilemusicplayer.data.local.dao.LyricsCacheDao
import com.androidexpert35.audiophilemusicplayer.data.local.entity.LyricsCacheEntity
import com.androidexpert35.audiophilemusicplayer.data.mapper.LrcParser
import com.androidexpert35.audiophilemusicplayer.data.remote.api.LrcLibApiService
import com.androidexpert35.audiophilemusicplayer.di.IoDispatcher
import com.androidexpert35.audiophilemusicplayer.domain.model.lyrics.Lyrics
import com.androidexpert35.audiophilemusicplayer.domain.repository.LyricsRepository
import com.tony.coreui.domain.resource.Resource
import com.tony.coreui.domain.resource.ResourceError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [LyricsRepository] implementation that combines a Room-backed cache with
 * live LRCLIB API lookups to serve synchronized and plain-text lyrics.
 *
 * ### Caching strategy
 * 1. The local Room cache is checked first; a hit (whether lyrics or a cached
 *    "not found" sentinel) avoids any network call.
 * 2. On a cache miss the LRCLIB API is queried.
 * 3. A `404` response is written to the cache as a "not found" sentinel so the
 *    same missing-lyrics query does not hit the network again.
 * 4. A successful response is parsed with [LrcParser] and written to the cache.
 * 5. Network or parsing failures are returned as [Resource.Error] without writing
 *    to the cache, allowing a future retry.
 *
 * @property lrcLibApiService Retrofit client pointing to `https://lrclib.net/api/`.
 * @property lyricsCacheDao DAO for reading cached lyrics and writing new entries.
 * @property ioDispatcher Injected IO dispatcher for all blocking work.
 * @constructor Created by Hilt via [com.androidexpert35.audiophilemusicplayer.di.RepositoryModule].
 */
@Singleton
class LyricsRepositoryImpl @Inject constructor(
    private val lrcLibApiService: LrcLibApiService,
    private val lyricsCacheDao: LyricsCacheDao,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : LyricsRepository {

    override suspend fun getLyrics(
        trackTitle: String,
        artistName: String,
        albumName: String,
        durationSeconds: Int,
    ): Resource<Lyrics> = withContext(ioDispatcher) {
        runCatching {
            val key = buildCacheKey(trackTitle, artistName, albumName, durationSeconds)

            // 1. Check the local cache — avoids network on both hits and known misses.
            val cached = lyricsCacheDao.getByKey(key)
            if (cached != null) {
                return@runCatching cached.toResource()
            }

            // 2. Fetch from LRCLIB.
            val response = lrcLibApiService.getLyrics(
                artistName = artistName,
                trackName = trackTitle,
                albumName = albumName,
                duration = durationSeconds,
            )

            if (response.code() == NOT_FOUND_CODE || response.body() == null) {
                // 3. Cache the "not found" sentinel so future calls skip the network.
                lyricsCacheDao.upsert(buildNotFoundEntity(key))
                return@runCatching Resource.Success(EMPTY_LYRICS)
            }

            val dto = response.body()!!
            val syncedLines = dto.syncedLyrics
                ?.takeIf { it.isNotBlank() }
                ?.let { LrcParser.parse(it) }
                ?: emptyList()

            // 4. Write successful result to cache.
            lyricsCacheDao.upsert(
                LyricsCacheEntity(
                    cacheKey = key,
                    syncedLyricsRaw = dto.syncedLyrics,
                    plainLyrics = dto.plainLyrics,
                    isInstrumental = dto.instrumental ?: false,
                    notFound = false,
                    fetchedAtMs = System.currentTimeMillis(),
                )
            )

            Resource.Success(
                Lyrics(
                    lines = syncedLines,
                    plainLyrics = dto.plainLyrics,
                    isInstrumental = dto.instrumental ?: false,
                )
            )
        }.getOrElse { throwable ->
            Resource.Error(
                ResourceError.NetworkError(
                    throwable.message ?: "Lyrics fetch failed"
                )
            )
        }
    }

    // ---------------------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------------------

    /**
     * Builds a stable, lowercase lookup key from the four track-identity fields.
     *
     * Lowercasing normalises minor capitalisation differences between MediaStore
     * metadata and LRCLIB's search results.
     */
    private fun buildCacheKey(
        trackTitle: String,
        artistName: String,
        albumName: String,
        durationSeconds: Int,
    ): String = "${trackTitle.lowercase()}|${artistName.lowercase()}|" +
        "${albumName.lowercase()}|$durationSeconds"

    /**
     * Builds a sentinel cache entity representing a known-missing lyrics entry.
     *
     * Cached "not found" rows are served instantly on future requests for the same
     * key, eliminating redundant network round-trips.
     */
    private fun buildNotFoundEntity(key: String) = LyricsCacheEntity(
        cacheKey = key,
        syncedLyricsRaw = null,
        plainLyrics = null,
        isInstrumental = false,
        notFound = true,
        fetchedAtMs = System.currentTimeMillis(),
    )

    /** Converts a cached entity back into a [Resource]. */
    private fun LyricsCacheEntity.toResource(): Resource<Lyrics> {
        if (notFound) return Resource.Success(EMPTY_LYRICS)
        val syncedLines = syncedLyricsRaw
            ?.takeIf { it.isNotBlank() }
            ?.let { LrcParser.parse(it) }
            ?: emptyList()
        return Resource.Success(
            Lyrics(
                lines = syncedLines,
                plainLyrics = plainLyrics,
                isInstrumental = isInstrumental,
            )
        )
    }

    private companion object {
        /** HTTP status code returned by LRCLIB when no track match was found. */
        const val NOT_FOUND_CODE = 404

        /** Shared sentinel value returned for both cached and live "not found" outcomes. */
        val EMPTY_LYRICS = Lyrics(lines = emptyList(), plainLyrics = null, isInstrumental = false)
    }
}


