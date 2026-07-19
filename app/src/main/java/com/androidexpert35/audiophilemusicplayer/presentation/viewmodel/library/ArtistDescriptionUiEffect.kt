package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library

/**
 * One-shot side-effects emitted from the artist description ViewModel.
 *
 * Effects are transient and consumed exactly once by the UI layer.
 */
sealed interface ArtistDescriptionUiEffect {

    /**
     * Indicates that a playback command failed for the requested artist or track action.
     *
     * @property message Human-readable playback error presented to the user via a snackbar.
     */
    data class PlaybackError(val message: String) : ArtistDescriptionUiEffect
}

