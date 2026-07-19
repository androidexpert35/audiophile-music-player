package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.search

/**
 * One-shot side-effects emitted from [SearchViewModel] to the UI layer.
 */
sealed interface SearchUiEffect {

    /**
     * A playback command triggered from search results failed.
     *
     * @property message Human-readable description of the failure shown in the UI.
     */
    data class PlaybackError(val message: String) : SearchUiEffect
}

