package com.androidexpert35.audiophilemusicplayer.domain.model.library

/**
 * Represents a locally stored M3U playlist.
 *
 * @property id Stable file-backed identifier used when modifying the playlist.
 * @property name User-visible playlist name.
 * @property trackUris Audio content URIs kept in their M3U playback order.
 * @property kind Semantic role used to select system-managed playlist behaviour and artwork.
 */
data class Playlist(
    val id: String,
    val name: String,
    val trackUris: List<String>,
    val kind: PlaylistKind = PlaylistKind.STANDARD
)
