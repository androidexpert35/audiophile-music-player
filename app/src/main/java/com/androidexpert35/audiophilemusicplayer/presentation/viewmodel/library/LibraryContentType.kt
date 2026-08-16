package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library

import com.androidexpert35.audiophilemusicplayer.R

/**
 * Enumerates the primary catalogue sections available on the library screen.
 *
 * Each value corresponds to one of the Spotify-style filter chips rendered in
 * [LibraryFilterChipsRow]. The default [TRACKS] section is shown when the
 * library opens. Only one section is active at a time. Declaration order here is
 * only the first-install default — the section's actual visibility and display
 * order are user preferences, see
 * [com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveLibrarySectionOrderUseCase].
 *
 * @property labelRes String resource for the section's filter chip label.
 */
enum class LibraryContentType(val labelRes: Int) {

    /** Default Songs section — displays the full local track catalogue. */
    TRACKS(R.string.library_tracks_section_label),

    /** Playlist section — reserved for future playlist creation; shows a placeholder. */
    PLAYLISTS(R.string.library_playlists_section_label),

    /** Albums section — displays indexed albums from the local library. */
    ALBUMS(R.string.library_albums_section_label),

    /** Artists section — displays indexed artists from the local library. */
    ARTISTS(R.string.library_artists_section_label),

    /** Genres section — groups indexed tracks by their source genre tags. */
    GENRES(R.string.library_genres_section_label),

    /** Years section — groups indexed tracks by their source release years. */
    YEARS(R.string.library_years_section_label),

    /** Composers section — groups indexed tracks by their source composer tags. */
    COMPOSERS(R.string.library_composers_section_label),

}
