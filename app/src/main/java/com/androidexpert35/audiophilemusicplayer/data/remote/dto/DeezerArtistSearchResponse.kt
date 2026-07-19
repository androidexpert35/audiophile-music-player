package com.androidexpert35.audiophilemusicplayer.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * Top-level Deezer response for `GET /search/artist?q={query}`.
 *
 * The API returns a paginated list. Callers must validate candidate identity
 * instead of trusting result order.
 *
 * @property data List of artist results returned by Deezer. May be empty when
 *   no artist matches the query.
 * @property total Total number of results available on the server.
 */
data class DeezerArtistSearchResponse(
    @SerializedName("data") val data: List<DeezerArtistDto> = emptyList(),
    @SerializedName("total") val total: Int = 0
)

/**
 * A single artist entry within the Deezer search result list.
 *
 * @property id Deezer-internal artist identifier.
 * @property name Display name of the artist on Deezer.
 * @property pictureXl Highest-resolution artist photo (1000 × 1000 px JPEG).
 *   Preferred over [pictureBig] when available and non-blank.
 * @property pictureBig Large artist photo (400 × 400 px JPEG), used as a
 *   fallback when [pictureXl] is absent.
 * @property fanCount Deezer follower count used only to disambiguate multiple
 *   candidates whose normalised names exactly match the local artist.
 */
data class DeezerArtistDto(
    @SerializedName("id") val id: Long = 0L,
    @SerializedName("name") val name: String = "",
    @SerializedName("picture_xl") val pictureXl: String? = null,
    @SerializedName("picture_big") val pictureBig: String? = null,
    @SerializedName("nb_fan") val fanCount: Long = 0L
)
