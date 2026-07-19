package com.androidexpert35.audiophilemusicplayer.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * JSON response model for the LRCLIB `/api/get` endpoint.
 *
 * Only the fields relevant to the lyrics feature are mapped; additional
 * fields returned by the API are silently ignored by Gson.
 *
 * @property id LRCLIB internal track identifier.
 * @property instrumental `true` when the API considers the track to have no vocals.
 * @property plainLyrics Full unformatted lyrics string, or `null` when unavailable.
 * @property syncedLyrics LRC-format string with one `[mm:ss.xx] text` line per lyric
 *   line, or `null` when the API has no synced version.
 */
data class LrcLibLyricsDto(
    @SerializedName("id")
    val id: Int?,
    @SerializedName("instrumental")
    val instrumental: Boolean?,
    @SerializedName("plainLyrics")
    val plainLyrics: String?,
    @SerializedName("syncedLyrics")
    val syncedLyrics: String?,
)

