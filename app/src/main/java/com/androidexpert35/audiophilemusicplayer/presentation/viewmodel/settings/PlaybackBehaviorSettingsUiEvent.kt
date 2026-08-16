package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings

/**
 * User intents emitted from the Playback Behavior settings sub-screen.
 */
sealed interface PlaybackBehaviorSettingsUiEvent {
    /** Select whether removing the app task from recents clears the playback queue. */
    data class SetClearQueueOnExit(val enabled: Boolean) : PlaybackBehaviorSettingsUiEvent
}
