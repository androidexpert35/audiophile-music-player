package com.androidexpert35.audiophilemusicplayer.data.mapper

import com.androidexpert35.audiophilemusicplayer.domain.model.lyrics.LyricLine

/**
 * Parses LRC-format lyric strings into sorted [LyricLine] lists.
 *
 * The LRC format uses one line per lyric entry in the form `[mm:ss.xx] text`.
 * This parser is pure Kotlin with no Android dependencies so it can be used
 * and tested without a device or emulator.
 *
 * ### Timestamp conversion
 * `(mm × 60 + ss) × 1000 + xx × 10` → milliseconds
 *
 * Lines that do not match the timestamp pattern (e.g. metadata tags like
 * `[ti:Title]`) are silently ignored.
 */
object LrcParser {

    /**
     * Regex matching a standard LRC timestamp `[mm:ss.xx]` followed by optional text.
     *
     * Groups:
     * 1. Minutes (`mm`) — two or more digits
     * 2. Seconds (`ss`) — exactly two digits
     * 3. Centiseconds (`xx`) — exactly two digits
     * 4. Lyric text — everything after the closing `]`, trimmed by the caller
     */
    private val LRC_TIMESTAMP_REGEX = Regex("""^\[(\d{2,}):(\d{2})\.(\d{2})\](.*)""")

    /**
     * Converts a raw LRC string into a list of [LyricLine] objects sorted by
     * ascending [LyricLine.timestampMs].
     *
     * Blank or un-parseable lines are dropped. The caller may filter out empty
     * text lines if the UI should not render gap markers.
     *
     * @param lrc Raw LRC-format string as returned by the lyrics API.
     * @return Sorted list of parsed lyric lines; empty when [lrc] is blank or
     *   contains no parseable timestamp lines.
     */
    fun parse(lrc: String): List<LyricLine> {
        if (lrc.isBlank()) return emptyList()

        return lrc.lines()
            .mapNotNull { line -> parseLine(line) }
            .sortedBy { it.timestampMs }
    }

    /**
     * Attempts to parse a single LRC line.
     *
     * @param line One text line from the LRC string.
     * @return A [LyricLine] on a successful parse, `null` when the line does not
     *   match the expected `[mm:ss.xx]` timestamp pattern.
     */
    private fun parseLine(line: String): LyricLine? {
        val match = LRC_TIMESTAMP_REGEX.matchEntire(line.trim()) ?: return null
        val (mm, ss, xx, text) = match.destructured
        val timestampMs = (mm.toLong() * 60 + ss.toLong()) * 1_000 + xx.toLong() * 10
        return LyricLine(timestampMs = timestampMs, text = text.trim())
    }
}

