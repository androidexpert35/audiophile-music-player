package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.player

import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track

/**
 * One-shot side-effects emitted from [PlayerViewModel] to the UI layer.
 */
sealed interface PlayerUiEffect {

    /**
     * Indicates a playback error that the UI should display transiently (e.g., snackbar).
     *
     * @property message Human-readable error description.
     */
    data class PlaybackError(val message: String) : PlayerUiEffect

    /**
     * Confirms that exclusive USB ownership has been released.
     *
     * @property message Localized confirmation shown in the player snackbar.
     */
    data class UsbAudioReleased(val message: String) : PlayerUiEffect

    /** Requests removal of the application task after USB teardown succeeds. */
    data object ExitApplication : PlayerUiEffect

    /**
     * Indicates that the current track has changed (e.g., for scroll-to-now-playing).
     *
     * @property track The newly active track.
     */
    data class TrackChanged(val track: Track) : PlayerUiEffect
}
