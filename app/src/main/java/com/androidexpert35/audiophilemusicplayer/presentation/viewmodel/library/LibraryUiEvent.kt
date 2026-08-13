package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library

import com.androidexpert35.audiophilemusicplayer.domain.model.track.Album
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Artist
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track

/**
 * User intents emitted from the library browser screen.
 */
sealed interface LibraryUiEvent {

    /** Switches the visible library section between songs, playlists, albums, and artists. */
    data class SelectContentType(val contentType: LibraryContentType) : LibraryUiEvent

    /** Load all tracks from the device. */
    data object LoadTracks : LibraryUiEvent

    /** Load all albums from the device. */
    data object LoadAlbums : LibraryUiEvent

    /** Load all artists from the device. */
    data object LoadArtists : LibraryUiEvent

    /**
     * Lazily enriches a visible artist row with its cached or remote image.
     *
     * @property artist Visible artist whose placeholder should be populated.
     */
    data class LoadArtistImage(val artist: Artist) : LibraryUiEvent

    /** Reload the entire library (tracks + albums + artists). */
    data object RefreshLibrary : LibraryUiEvent

    /** Opens the dialog used to create a new local M3U playlist. */
    data object ShowCreatePlaylistDialog : LibraryUiEvent

    /** Closes the dialog used to create a new local M3U playlist. */
    data object DismissCreatePlaylistDialog : LibraryUiEvent

    /**
     * Persists an empty local M3U playlist.
     *
     * @property name Name entered by the user.
     */
    data class CreatePlaylist(val name: String) : LibraryUiEvent

    /**
     * Opens the reusable playlist selector for a selected track.
     *
     * @property track Track the user wants to add.
     */
    data class ShowPlaylistPicker(val track: Track) : LibraryUiEvent

    /** Closes the playlist selector without changing a playlist. */
    data object DismissPlaylistPicker : LibraryUiEvent

    /**
     * Adds the selected track to an existing playlist.
     *
     * @property playlistId File-backed target playlist identifier.
     */
    data class AddTrackToPlaylist(val playlistId: String) : LibraryUiEvent

    /** Inserts a selected song immediately after the active playback item. */
    data class PlayNext(val track: Track) : LibraryUiEvent

    /** Appends a selected song to the end of the active playback queue. */
    data class AddToQueue(val track: Track) : LibraryUiEvent

    /** Toggles between list and two-column grid view modes. */
    data object ToggleViewMode : LibraryUiEvent

    /**
     * Changes the retained sort order for the currently visible catalogue section.
     *
     * @property sortOrder New sort strategy to apply immediately.
     */
    data class SetSortOrder(val sortOrder: LibrarySortOrder) : LibraryUiEvent

    /**
     * Toggles the liked state of the given track.
     *
     * @property track The track whose like status should be flipped.
     */
    data class ToggleLikeSong(val track: Track) : LibraryUiEvent

    // --- Navigation intents ---

    /** Navigate to the search screen. */
    data object OpenSearch : LibraryUiEvent

    /** Navigate to the settings screen. */
    data object OpenSettings : LibraryUiEvent

    /** Opens the detailed album overview screen for the selected album. */
    data class OpenAlbumOverview(val album: Album) : LibraryUiEvent

    /** Opens the detailed playlist overview screen for the selected M3U playlist. */
    data class OpenPlaylistOverview(val playlist: PlaylistUiModel) : LibraryUiEvent

    /** Opens the Spotify-style artist profile screen for the selected artist. */
    data class OpenArtistDescription(val artist: Artist) : LibraryUiEvent

    /** Opens the album overview screen for the given track's album. */
    data class OpenTrackAlbum(val track: Track) : LibraryUiEvent

    /** Opens the artist profile screen for the given track's artist. */
    data class OpenTrackArtist(val track: Track) : LibraryUiEvent

    /**
     * Play a specific album starting from its first track.
     *
     * @property album The album to start playing.
     */
    data class PlayAlbum(val album: Album) : LibraryUiEvent

    /**
     * Play a specific track within the full library queue.
     *
     * @property track The track to start playing.
     */
    data class PlayTrack(val track: Track) : LibraryUiEvent
}
