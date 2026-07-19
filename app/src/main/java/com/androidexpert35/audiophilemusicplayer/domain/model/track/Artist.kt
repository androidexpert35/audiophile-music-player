package com.androidexpert35.audiophilemusicplayer.domain.model.track

import java.util.Locale

/**
 * Domain model representing a music artist.
 *
 * @property id Unique MediaStore identifier for this artist.
 * @property name Display name of the artist.
 * @property albumCount Number of albums attributed to this artist.
 * @property trackCount Number of tracks attributed to this artist.
 * @property imageUrl Remote image URL fetched from Deezer, or `null` when not yet enriched
 *   or when the artist could not be found on Deezer.
 */
data class Artist(
    val id: Long,
    val name: String,
    val albumCount: Int,
    val trackCount: Int,
    val imageUrl: String? = null
)

/**
 * Identifies synthetic artist names used when local metadata has no real credit.
 *
 * These sentinels must never be sent to remote enrichment services because they
 * can accidentally match generic catalogue profiles and produce misleading art.
 *
 * @return `true` when this value represents a missing artist identity.
 */
fun String.isUnknownArtistName(): Boolean {
    val normalized = trim().lowercase(Locale.ROOT)
    return normalized.isBlank() ||
        normalized == UNKNOWN_ARTIST_NAME ||
        normalized == MEDIA_STORE_UNKNOWN_ARTIST
}

private const val UNKNOWN_ARTIST_NAME = "unknown artist"
private const val MEDIA_STORE_UNKNOWN_ARTIST = "<unknown>"

