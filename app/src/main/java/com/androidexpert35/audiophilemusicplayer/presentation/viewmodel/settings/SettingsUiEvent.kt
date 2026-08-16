package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings

import com.androidexpert35.audiophilemusicplayer.presentation.screen.settings.components.SettingsCategory

/**
 * User intents emitted from the Settings hub screen.
 */
sealed interface SettingsUiEvent {
    /** Opens the sub-screen for the tapped category. */
    data class OpenCategory(val category: SettingsCategory) : SettingsUiEvent
}
