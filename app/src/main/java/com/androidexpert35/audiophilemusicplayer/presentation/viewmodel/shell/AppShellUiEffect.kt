package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.shell

/**
 * One-shot side-effects emitted from [AppShellViewModel] to the UI layer.
 */
sealed interface AppShellUiEffect {

    /**
     * Indicates a playback error originating from mini-player controls.
     *
     * @property message Human-readable error description.
     */
    data class PlaybackError(val message: String) : AppShellUiEffect
}

