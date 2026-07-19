package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library

import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track

/**
 * Describes listener actions on a playlist detail page.
 */
sealed interface PlaylistOverviewUiEvent {
    /** Loads the playlist identified by its M3U filename. */
    data class Initialize(val playlistId: String) : PlaylistOverviewUiEvent

    /** Updates the in-playlist song filter. */
    data class UpdateSearchQuery(val query: String) : PlaylistOverviewUiEvent

    /** Enters or leaves the focused playlist-search presentation. */
    data class SetSearchActive(val isActive: Boolean) : PlaylistOverviewUiEvent

    /** Clears the filter and returns to the standard playlist overview. */
    data object CloseSearch : PlaylistOverviewUiEvent

    /** Starts sequential playback from the first playlist track. */
    data object PlayPlaylist : PlaylistOverviewUiEvent

    /** Starts playback using a randomised version of the playlist order. */
    data object ShufflePlaylist : PlaylistOverviewUiEvent

    /** Starts the selected track within the playlist queue. */
    data class PlayTrack(val track: Track) : PlaylistOverviewUiEvent

    /** Inserts the selected playlist track after the active playback item. */
    data class PlayNext(val track: Track) : PlaylistOverviewUiEvent

    /** Appends the selected playlist track to the active playback queue. */
    data class AddToQueue(val track: Track) : PlaylistOverviewUiEvent

    /** Opens the album overview screen for the given track's album. */
    data class OpenTrackAlbum(val track: Track) : PlaylistOverviewUiEvent

    /** Opens the artist profile screen for the given track's artist. */
    data class OpenTrackArtist(val track: Track) : PlaylistOverviewUiEvent

    /** Enters manual ordering, or confirms and saves the current manual order. */
    data object ToggleEditing : PlaylistOverviewUiEvent

    /** Applies one in-memory drag-and-drop move. */
    data class MoveTrack(val fromIndex: Int, val toIndex: Int) : PlaylistOverviewUiEvent

    /** Removes one playlist entry during an active edit session. */
    data class RemoveTrack(val index: Int) : PlaylistOverviewUiEvent

    /** Returns to the previous destination. */
    data object NavigateBack : PlaylistOverviewUiEvent
}
