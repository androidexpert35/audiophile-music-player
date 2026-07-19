package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings

/**
 * One-shot effects emitted from the Settings ViewModel.
 */
sealed interface SettingsUiEffect {

	/**
	 * Requests a transient error surface when the playback-engine swap fails.
	 *
	 * @property message Human-readable failure message shown to the user.
	 */
	data class ToggleError(val message: String) : SettingsUiEffect
}
