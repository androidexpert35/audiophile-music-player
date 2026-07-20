package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library

/**
 * Emits transient feedback from the playlist detail page.
 */
sealed interface PlaylistOverviewUiEffect {
    /**
     * @property message Human-readable reason the requested action could not complete.
     */
    data class Message(val message: String) : PlaylistOverviewUiEffect

    /** Confirms that a playlist edit was saved to the local M3U file. */
    data object PlaylistUpdated : PlaylistOverviewUiEffect

    /** Confirms that the playlist was permanently deleted. */
    data object PlaylistDeleted : PlaylistOverviewUiEffect
}
