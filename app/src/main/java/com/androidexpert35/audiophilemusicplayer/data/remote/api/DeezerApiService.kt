package com.androidexpert35.audiophilemusicplayer.data.remote.api

import com.androidexpert35.audiophilemusicplayer.data.remote.dto.DeezerAlbumSearchResponse
import com.androidexpert35.audiophilemusicplayer.data.remote.dto.DeezerArtistSearchResponse
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Retrofit interface for the public, keyless Deezer REST API.
 *
 * Base URL: `https://api.deezer.com/`
 *
 * No authentication is required. Rate limits apply per Deezer's fair-use policy;
 * results are cached in Room after the first successful fetch to minimise calls.
 *
 * @see <a href="https://developers.deezer.com/api">Deezer API documentation</a>
 */
interface DeezerApiService {

    /**
     * Searches Deezer's artist catalogue for the best match to [query].
     *
     * Example request: `GET /search/artist?q=Miles+Davis`
     *
     * The first result in the returned list is used for image extraction; the
     * caller should treat an empty [DeezerArtistSearchResponse.data] list as
     * "no result found" and avoid caching in that case.
     *
     * @param query Artist name to search for, URL-encoded by Retrofit automatically.
     * @return Parsed search response containing ranked artist results.
     */
    @GET("search/artist")
    suspend fun searchArtist(
        @Query("q") query: String
    ): DeezerArtistSearchResponse

    /**
     * Searches Deezer's album catalogue using a combined artist + album query.
     *
     * Example request: `GET /search/album?q=artist:"Miles Davis" album:"Kind of Blue"`
     *
     * The first result in the returned list is used for cover extraction; the
     * caller should treat an empty [DeezerAlbumSearchResponse.data] list as
     * "no result found" and avoid caching in that case.
     *
     * @param query Combined search query. Format: `artist:"name" album:"title"`.
     *   Retrofit encodes the value automatically.
     * @return Parsed search response containing ranked album results.
     */
    @GET("search/album")
    suspend fun searchAlbum(
        @Query("q") query: String
    ): DeezerAlbumSearchResponse
}

