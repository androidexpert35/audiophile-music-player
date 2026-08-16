package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings

/**
 * One-shot effects emitted from the USB &amp; DAC settings ViewModel.
 */
sealed interface UsbSettingsUiEffect {
    /**
     * Requests a transient error surface when a USB command fails.
     *
     * @property message Human-readable failure message shown to the user.
     */
    data class ToggleError(val message: String) : UsbSettingsUiEffect
}
