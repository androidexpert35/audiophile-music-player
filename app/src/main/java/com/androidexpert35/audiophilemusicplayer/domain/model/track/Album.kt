package com.androidexpert35.audiophilemusicplayer.domain.model.track

/**
 * Domain model representing a music album.
 *
 * @property id Unique MediaStore identifier for this album.
 * @property title Display title of the album.
 * @property artistName Name of the album artist.
 * @property artUri Content URI string for the local album artwork, or `null` if unavailable.
 * @property remoteArtUrl HTTPS URL for the album cover fetched from Deezer, used as a
 *   fallback when the local [artUri] resolves to no embedded artwork. `null` when not
 *   yet enriched or when Deezer returned no match.
 * @property trackCount Total number of tracks in this album.
 * @property year Release year, or `0` if unknown.
 */
data class Album(
    val id: Long,
    val title: String,
    val artistName: String,
    val artUri: String?,
    val remoteArtUrl: String? = null,
    val trackCount: Int,
    val year: Int
)



