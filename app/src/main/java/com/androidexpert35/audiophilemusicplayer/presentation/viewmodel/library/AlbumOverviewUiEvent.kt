package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library

import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track

/**
 * User intents emitted from the album overview / detail screen.
 */
sealed interface AlbumOverviewUiEvent {
    /** Loads the album overview for the provided album identifier. */
    data class Initialize(val albumId: Long) : AlbumOverviewUiEvent

    /** Starts sequential playback from the album's first available track. */
    data object PlayAlbum : AlbumOverviewUiEvent

    /** Starts a shuffled playback session using all tracks in the album queue. */
    data object ShuffleAlbum : AlbumOverviewUiEvent

    /** Starts playback from the selected track within the album queue. */
    data class PlayTrack(val track: Track) : AlbumOverviewUiEvent

    /** Inserts the selected track immediately after the active playback item. */
    data class PlayNext(val track: Track) : AlbumOverviewUiEvent

    /** Appends the selected track to the end of the active playback queue. */
    data class AddToQueue(val track: Track) : AlbumOverviewUiEvent

    /** Inserts the complete ordered album immediately after the active item. */
    data object PlayAlbumNext : AlbumOverviewUiEvent

    /** Appends the complete ordered album to the end of the active queue. */
    data object AddAlbumToQueue : AlbumOverviewUiEvent

    /** Toggles the liked / saved state for the currently displayed album. */
    data object ToggleLike : AlbumOverviewUiEvent

    /** Requests the track-level options bottom sheet for the given track. */
    data class ShowTrackOptions(val track: Track) : AlbumOverviewUiEvent

    /** Opens the playlist selector for adding the complete album. */
    data object ShowAlbumPlaylistPicker : AlbumOverviewUiEvent

    /** Closes the playlist selector without modifying a playlist. */
    data object DismissPlaylistPicker : AlbumOverviewUiEvent

    /**
     * Adds the track awaiting selection to the selected M3U playlist.
     *
     * @property playlistId File-backed target playlist identifier.
     */
    data class AddTrackToPlaylist(val playlistId: String) : AlbumOverviewUiEvent

    /** Navigates to the artist description screen for the album's primary artist. */
    data class OpenArtist(val artistName: String) : AlbumOverviewUiEvent

    /** Returns to the previous destination. */
    data object NavigateBack : AlbumOverviewUiEvent
}
