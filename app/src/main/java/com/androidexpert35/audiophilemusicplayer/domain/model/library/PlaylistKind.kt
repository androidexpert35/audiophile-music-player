package com.androidexpert35.audiophilemusicplayer.domain.model.library

/**
 * Distinguishes listener-created playlists from app-managed playlist collections.
 */
enum class PlaylistKind {
    /** A regular playlist created and edited explicitly by the listener. */
    STANDARD,

    /** The reserved M3U playlist whose membership mirrors liked-song state. */
    FAVORITES,

    /**
     * Discovered from a `.m3u`/`.m3u8` file inside a granted music folder. Fully
     * editable like [STANDARD], but edits are written back to that original file
     * instead of an app-private copy.
     */
    IMPORTED
}
