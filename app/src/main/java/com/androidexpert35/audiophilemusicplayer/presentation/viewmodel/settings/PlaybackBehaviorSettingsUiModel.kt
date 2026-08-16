package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings

/**
 * Immutable UI state for the Playback Behavior settings sub-screen.
 *
 * @property clearQueueOnExit Whether the playback queue is cleared when the app is
 *   removed from Recents, instead of being restored on the next launch.
 */
data class PlaybackBehaviorSettingsUiModel(
    val clearQueueOnExit: Boolean = false,
)
