package com.androidexpert35.audiophilemusicplayer.data.remote.api

import com.androidexpert35.audiophilemusicplayer.data.remote.dto.LrcLibLyricsDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Retrofit interface for the public, keyless LRCLIB lyrics API.
 *
 * Base URL: `https://lrclib.net/api/`
 *
 * No authentication is required. The API is free to use under the LRCLIB
 * project terms. Results should be cached locally after the first successful
 * fetch to minimise network calls.
 *
 * @see <a href="https://lrclib.net/docs">LRCLIB API documentation</a>
 */
interface LrcLibApiService {

    /**
     * Looks up synchronized and plain-text lyrics for a specific track.
     *
     * A `404 Not Found` response indicates the API has no match for the given
     * metadata combination; the caller should treat this as "lyrics unavailable"
     * rather than an error.
     *
     * Example request:
     * `GET /api/get?artist_name=Miles+Davis&track_name=So+What&album_name=Kind+of+Blue&duration=565`
     *
     * @param artistName Performing artist name.
     * @param trackName Track title.
     * @param albumName Album title.
     * @param duration Track duration in whole seconds.
     * @return A [Response] wrapping [LrcLibLyricsDto] on success, or an
     *   empty body with a `404` status when no match is found.
     */
    @GET("get")
    suspend fun getLyrics(
        @Query("artist_name") artistName: String,
        @Query("track_name") trackName: String,
        @Query("album_name") albumName: String,
        @Query("duration") duration: Int,
    ): Response<LrcLibLyricsDto>

    /**
     * Fuzzy-searches the catalogue when [getLyrics] finds no exact match.
     *
     * Unlike `/api/get`, this endpoint tolerates metadata drift — a different
     * album title, a `(Remastered)` suffix, a missing featured artist, or a
     * duration that is off by more than the exact matcher allows. It returns up
     * to 20 candidates ordered by relevance, and an empty array (not a `404`)
     * when nothing matches, so the caller must rank the results itself.
     *
     * Example request:
     * `GET /api/search?track_name=So+What&artist_name=Miles+Davis`
     *
     * @param trackName Track title, ideally stripped of edition suffixes.
     * @param artistName Performing artist name, or `null` to search on title alone.
     * @return A [Response] wrapping the candidate list; empty when no match exists.
     */
    @GET("search")
    suspend fun searchLyrics(
        @Query("track_name") trackName: String,
        @Query("artist_name") artistName: String?,
    ): Response<List<LrcLibLyricsDto>>
}

