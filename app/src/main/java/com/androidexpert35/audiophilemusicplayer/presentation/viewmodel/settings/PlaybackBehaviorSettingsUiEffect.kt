package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings

/**
 * One-shot effects emitted from the Playback Behavior settings ViewModel.
 */
sealed interface PlaybackBehaviorSettingsUiEffect {
    /**
     * Requests a transient error surface when the preference write fails.
     *
     * @property message Human-readable failure message shown to the user.
     */
    data class ToggleError(val message: String) : PlaybackBehaviorSettingsUiEffect
}
