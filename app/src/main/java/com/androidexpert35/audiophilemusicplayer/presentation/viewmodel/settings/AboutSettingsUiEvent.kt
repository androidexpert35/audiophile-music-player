package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings

/** User actions in App information. */
sealed interface AboutSettingsUiEvent {
    /** Opens a session diagnostic email for user review and sending. */
    data object ReportBug : AboutSettingsUiEvent
}
