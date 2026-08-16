package com.androidexpert35.audiophilemusicplayer.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * JSON response model shared by the LRCLIB `/api/get` and `/api/search` endpoints,
 * which return the same object shape (search simply returns an array of them).
 *
 * Only the fields relevant to the lyrics feature are mapped; additional
 * fields returned by the API are silently ignored by Gson.
 *
 * @property id LRCLIB internal track identifier.
 * @property trackName Track title as stored by LRCLIB; used to rank search candidates.
 * @property artistName Artist name as stored by LRCLIB; used to rank search candidates.
 * @property duration Track length in seconds, sent by the API as a fractional number.
 *   Used to pick the search candidate closest to the local file's real duration.
 * @property instrumental `true` when the API considers the track to have no vocals.
 * @property plainLyrics Full unformatted lyrics string, or `null` when unavailable.
 * @property syncedLyrics LRC-format string with one `[mm:ss.xx] text` line per lyric
 *   line, or `null` when the API has no synced version.
 */
data class LrcLibLyricsDto(
    @SerializedName("id")
    val id: Int?,
    @SerializedName("trackName")
    val trackName: String?,
    @SerializedName("artistName")
    val artistName: String?,
    @SerializedName("duration")
    val duration: Double?,
    @SerializedName("instrumental")
    val instrumental: Boolean?,
    @SerializedName("plainLyrics")
    val plainLyrics: String?,
    @SerializedName("syncedLyrics")
    val syncedLyrics: String?,
)

