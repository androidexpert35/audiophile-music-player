package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library

/**
 * One-shot effects emitted from the album overview ViewModel.
 */
sealed interface AlbumOverviewUiEffect {
    /**
     * Indicates that a playback action failed and should be surfaced to the user.
     *
     * @property message Human-readable playback failure description.
     */
    data class PlaybackError(val message: String) : AlbumOverviewUiEffect

    /**
     * Confirms that an album track was inserted into the active playback queue.
     *
     * @property message Localized description of the completed queue action.
     */
    data class QueueUpdated(val message: String) : AlbumOverviewUiEffect

    /**
     * Confirms that an album track was added to a local playlist.
     *
     * @property playlistName Name of the target playlist.
     * @property trackTitle Title of the added album track.
     */
    data class TrackAddedToPlaylist(
        val playlistName: String,
        val trackTitle: String
    ) : AlbumOverviewUiEffect

    /**
     * Confirms that every track in the album was added to a local playlist.
     *
     * @property playlistName Name of the target playlist.
     * @property albumTitle Display title of the appended album.
     */
    data class AlbumAddedToPlaylist(
        val playlistName: String,
        val albumTitle: String
    ) : AlbumOverviewUiEffect
}
