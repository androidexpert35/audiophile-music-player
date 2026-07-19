package com.androidexpert35.audiophilemusicplayer.domain.model.track

/**
 * Sorts tracks in the canonical album display order:
 * disc number ascending → track number ascending (unset tracks last) → title ascending.
 *
 * Tracks that carry a track-number value of `0` or less are considered unnumbered and
 * are sorted after all explicitly numbered tracks within the same disc, then
 * alphabetically by title as a tiebreaker.
 *
 * This ordering is used whenever an album or artist queue must preserve the composer's
 * intended listening sequence (album detail views, album playback queues, and artist
 * profile queues grouped by album).
 *
 * Pure Kotlin — safe to call from the domain or presentation layers.
 *
 * @return A new list with the same elements sorted in album order.
 */
fun List<Track>.sortedByAlbumOrder(): List<Track> = sortedWith(
    compareBy<Track> { it.discNumber }
        .thenBy { track -> if (track.trackNumber > 0) track.trackNumber else Int.MAX_VALUE }
        .thenBy { it.title }
)

