package com.androidexpert35.audiophilemusicplayer.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * Top-level Deezer response for `GET /search/album?q={query}`.
 *
 * The API returns a paginated list; only the first result is used for
 * artwork enrichment since the highest-ranked match is typically the best fit.
 *
 * @property data List of album results returned by Deezer. May be empty when
 *   no album matches the query.
 * @property total Total number of results available on the server.
 */
data class DeezerAlbumSearchResponse(
    @SerializedName("data") val data: List<DeezerAlbumDto> = emptyList(),
    @SerializedName("total") val total: Int = 0
)

/**
 * A single album entry within the Deezer search result list.
 *
 * @property id Deezer-internal album identifier.
 * @property title Display title of the album on Deezer.
 * @property coverXl Highest-resolution album cover (1000 × 1000 px JPEG).
 *   Preferred over [coverBig] when available and non-blank.
 * @property coverBig Large album cover (400 × 400 px JPEG), used as a fallback
 *   when [coverXl] is absent.
 */
data class DeezerAlbumDto(
    @SerializedName("id") val id: Long = 0L,
    @SerializedName("title") val title: String = "",
    @SerializedName("cover_xl") val coverXl: String? = null,
    @SerializedName("cover_big") val coverBig: String? = null
)

