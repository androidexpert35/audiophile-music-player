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
     * Indicates that the current track has changed (e.g., for scroll-to-now-playing).
     *
     * @property track The newly active track.
     */
    data class TrackChanged(val track: Track) : PlayerUiEffect
}

