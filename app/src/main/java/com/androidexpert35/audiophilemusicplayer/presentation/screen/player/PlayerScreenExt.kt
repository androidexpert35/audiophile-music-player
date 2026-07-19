package com.androidexpert35.audiophilemusicplayer.presentation.screen.player

import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track
import kotlin.math.roundToInt

/**
 * Builds the secondary marquee line for the now-playing metadata row.
 *
 * Concatenates the artist name and album title with a separator, omitting
 * blank or duplicate entries. Uses [String.map], [String.filter], and
 * Uses `map`, `filter`, and `joinToString` — call-site must wrap this in `remember(track.id)`
 * to avoid allocating on every recomposition.
 *
 * @param track Track whose metadata is formatted into the supporting string.
 * @return Formatted string such as `"Miles Davis • Kind of Blue"`, or a
 *   single segment when artist and album are identical.
 */
internal fun buildTrackSupportingText(track: Track): String =
    listOf(track.artistName, track.albumTitle)
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .joinToString(separator = " • ")

/**
 * Estimates the encoded bitrate from file size and duration until live telemetry
 * becomes available from the playback engine.
 *
 * Pure integer arithmetic — call-site must wrap in `remember(track.id)` to avoid
 * running this calculation on every recomposition.
 *
 * @param track Track whose [Track.fileSizeBytes] and [Track.durationMs] are used.
 * @return Estimated bitrate in kbps, or `0` when either field is not populated.
 */
internal fun estimateBitrateKbps(track: Track): Int {
    if (track.durationMs <= 0L || track.fileSizeBytes <= 0L) return 0
    val durationSeconds = track.durationMs / 1000.0
    val bitsPerSecond = (track.fileSizeBytes * 8.0) / durationSeconds
    return (bitsPerSecond / 1000.0).roundToInt()
}

