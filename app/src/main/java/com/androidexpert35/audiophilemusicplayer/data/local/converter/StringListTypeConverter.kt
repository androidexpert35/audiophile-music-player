package com.androidexpert35.audiophilemusicplayer.data.local.converter

import androidx.room.TypeConverter

/**
 * Room [TypeConverter] that serialises and deserialises `List<String>` as a
 * comma-separated [String].
 *
 * Used for [com.androidexpert35.audiophilemusicplayer.data.local.entity.ImportedPlaylistEntity.trackUris].
 * Content/document URIs are percent-encoded by their producing framework APIs, so a raw
 * comma never appears inside one and no escaping is required.
 */
class StringListTypeConverter {

    /**
     * Converts a list of content URI [String]s to a comma-separated [String] for storage.
     *
     * @param values The list to serialise.
     * @return Comma-separated string, or an empty string for an empty list.
     */
    @TypeConverter
    fun fromStringList(values: List<String>): String =
        if (values.isEmpty()) "" else values.joinToString(separator = ",")

    /**
     * Parses a comma-separated [String] back into a [List] of content URI strings.
     *
     * @param value The stored comma-separated string.
     * @return Parsed list of URIs; empty list for a blank value.
     */
    @TypeConverter
    fun toStringList(value: String): List<String> =
        if (value.isBlank()) emptyList()
        else value.split(",").map(String::trim)
}
