package com.androidexpert35.audiophilemusicplayer.domain.model.lyrics

/**
 * Resolved lyrics payload for a single audio track.
 *
 * A track can carry synced lines, plain text, or neither (when no match was found on
 * the remote service). The [isInstrumental] flag is surfaced directly from the API
 * so the UI can show a dedicated "instrumental" state instead of a loading placeholder.
 *
 * @property lines Time-stamped lyric lines sorted ascending by [LyricLine.timestampMs].
 *   Empty when the track has no synced lyrics (plain-text-only or instrumental).
 * @property plainLyrics Unformatted full lyrics string, or `null` when unavailable.
 * @property isInstrumental `true` when the API reports no vocals are present.
 */
data class Lyrics(
    val lines: List<LyricLine>,
    val plainLyrics: String?,
    val isInstrumental: Boolean,
)

