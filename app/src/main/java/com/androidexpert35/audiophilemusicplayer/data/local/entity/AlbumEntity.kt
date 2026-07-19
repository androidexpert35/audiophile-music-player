package com.androidexpert35.audiophilemusicplayer.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity representing an indexed album derived from scanned tracks.
 *
 * @property id Stable MediaStore album identifier.
 * @property title Album title presented in the library.
 * @property artistId Stable MediaStore artist identifier for the album artist.
 * @property artistName Album artist name.
 * @property artUri Content URI pointing to local album artwork when available.
 * @property remoteArtUrl Deezer album cover URL cached locally after the first
 *   successful remote lookup; used as a fallback when [artUri] yields no image.
 *   `null` until the album has been enriched.
 * @property trackCount Number of indexed tracks belonging to the album.
 * @property year Best-effort release year derived from MediaStore.
 * @property totalDurationMs Sum of the indexed track durations for the album.
 */
@Entity(
    tableName = "albums",
    indices = [
        Index(value = ["title"]),
        Index(value = ["artistName"])
    ]
)
data class AlbumEntity(
    @PrimaryKey val id: Long,
    val title: String,
    val artistId: Long,
    val artistName: String,
    val artUri: String?,
    val remoteArtUrl: String? = null,
    val trackCount: Int,
    val year: Int,
    val totalDurationMs: Long
)



