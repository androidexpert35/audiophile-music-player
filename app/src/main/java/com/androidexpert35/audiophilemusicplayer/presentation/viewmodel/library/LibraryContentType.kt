package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library

/**
 * Enumerates the primary catalogue sections available on the library screen.
 *
 * Each value corresponds to one of the Spotify-style filter chips rendered in
 * [LibraryFilterChipsRow]. The default [TRACKS] section is shown when the
 * library opens. Only one section is active at a time.
 */
enum class LibraryContentType {

    /** Default Songs section — displays the full local track catalogue. */
    TRACKS,

    /** Playlist section — reserved for future playlist creation; shows a placeholder. */
    PLAYLISTS,

    /** Albums section — displays indexed albums from the local library. */
    ALBUMS,

    /** Artists section — displays indexed artists from the local library. */
    ARTISTS

}
