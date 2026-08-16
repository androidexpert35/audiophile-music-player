package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings

/**
 * One-shot effects emitted from the Audio Engine &amp; DSP settings ViewModel.
 */
sealed interface AudioEngineSettingsUiEffect {
    /**
     * Requests a transient error surface when a toggle write fails.
     *
     * @property message Human-readable failure message shown to the user.
     */
    data class ToggleError(val message: String) : AudioEngineSettingsUiEffect
}
