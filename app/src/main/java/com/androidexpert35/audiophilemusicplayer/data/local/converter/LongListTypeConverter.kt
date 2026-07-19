package com.androidexpert35.audiophilemusicplayer.data.local.converter

import androidx.room.TypeConverter

/**
 * Room [TypeConverter] that serialises and deserialises `List<Long>` as a
 * comma-separated [String].
 *
 * Storing only IDs (not full metadata) keeps the `playback_state` table
 * small and avoids duplicating the track catalogue already held in the
 * `tracks` table.
 */
class LongListTypeConverter {

    /**
     * Converts a list of [Long] IDs to a comma-separated [String] for storage.
     *
     * @param ids The list to serialise.
     * @return Comma-separated ID string, or an empty string for an empty list.
     */
    @TypeConverter
    fun fromLongList(ids: List<Long>): String =
        if (ids.isEmpty()) "" else ids.joinToString(separator = ",")

    /**
     * Parses a comma-separated [String] back into a [List] of [Long] IDs.
     *
     * @param value The stored comma-separated string.
     * @return Parsed list of IDs; empty list for a blank value.
     */
    @TypeConverter
    fun toLongList(value: String): List<Long> =
        if (value.isBlank()) emptyList()
        else value.split(",").mapNotNull { it.trim().toLongOrNull() }
}

