package com.androidexpert35.audiophilemusicplayer.domain.model.lyrics

/**
 * A single time-stamped line of synchronized lyrics.
 *
 * @property timestampMs The absolute playback position at which this line should be
 *   highlighted, expressed in milliseconds from the start of the track.
 * @property text The display text for this lyric line. May be an empty string for
 *   intentional blank lines that separate lyric sections.
 */
data class LyricLine(
    val timestampMs: Long,
    val text: String,
)

