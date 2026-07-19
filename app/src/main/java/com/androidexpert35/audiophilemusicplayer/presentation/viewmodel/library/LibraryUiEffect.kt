package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library

/**
 * One-shot side-effects emitted from [LibraryViewModel] to the UI layer.
 */
sealed interface LibraryUiEffect {

    /**
     * Indicates that the library scan completed successfully.
     *
     * @property trackCount Total number of tracks indexed.
     */
    data class ScanComplete(val trackCount: Int) : LibraryUiEffect

    /**
     * Indicates that the library scan failed.
     *
     * @property message Human-readable error description.
     */
    data class ScanError(val message: String) : LibraryUiEffect

    /**
     * Indicates a playback error originating from the mini-player controls.
     *
     * @property message Human-readable error description.
     */
    data class PlaybackError(val message: String) : LibraryUiEffect

    /**
     * Confirms that a track was inserted into the active playback queue.
     *
     * @property message Localized description of the completed queue action.
     */
    data class QueueUpdated(val message: String) : LibraryUiEffect

    /**
     * Confirms a successful local playlist mutation with a short platform toast.
     *
     * @property playlistName Name of the playlist affected by the action.
     * @property trackTitle Added track title, or `null` when a playlist was created.
     */
    data class PlaylistSuccess(
        val playlistName: String,
        val trackTitle: String? = null
    ) : LibraryUiEffect
}
