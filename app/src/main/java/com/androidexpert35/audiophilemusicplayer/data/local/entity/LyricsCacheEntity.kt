package com.androidexpert35.audiophilemusicplayer.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity caching raw lyrics data keyed by a stable track fingerprint.
 *
 * The primary key [cacheKey] is a composite string derived from track title,
 * artist name, album name, and duration so results can be looked up without
 * requiring a MediaStore track ID (which is device-specific).
 *
 * @property cacheKey Stable lookup key composed from track metadata.
 * @property syncedLyricsRaw Raw LRC-format string as returned by the API, or `null`
 *   when the track has no synced lyrics.
 * @property plainLyrics Plain-text lyrics string, or `null` when unavailable.
 * @property isInstrumental `true` when the API reported no vocals.
 * @property notFound `true` when the API returned a 404 for this key, cached to
 *   avoid repeated network misses.
 * @property fetchedAtMs Epoch milliseconds of when this row was written.
 */
@Entity(tableName = "lyrics_cache")
data class LyricsCacheEntity(
    @PrimaryKey
    val cacheKey: String,
    val syncedLyricsRaw: String?,
    val plainLyrics: String?,
    val isInstrumental: Boolean,
    val notFound: Boolean,
    val fetchedAtMs: Long,
)

