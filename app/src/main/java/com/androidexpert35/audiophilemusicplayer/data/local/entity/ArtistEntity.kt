package com.androidexpert35.audiophilemusicplayer.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity representing an indexed artist derived from scanned tracks.
 *
 * @property id Stable identifier derived from the normalized artist name.
 * @property name Human-readable artist name.
 * @property albumCount Number of indexed albums attributed to the artist.
 * @property trackCount Number of indexed tracks attributed to the artist.
 * @property totalDurationMs Sum of the indexed track durations for the artist.
 * @property remoteImageUrl Deezer artist image URL cached locally after the first
 *   successful remote lookup; `null` until the artist has been enriched.
 */
@Entity(
    tableName = "artists",
    indices = [Index(value = ["name"])]
)
data class ArtistEntity(
    @PrimaryKey val id: Long,
    val name: String,
    val albumCount: Int,
    val trackCount: Int,
    val totalDurationMs: Long,
    val remoteImageUrl: String? = null
)



