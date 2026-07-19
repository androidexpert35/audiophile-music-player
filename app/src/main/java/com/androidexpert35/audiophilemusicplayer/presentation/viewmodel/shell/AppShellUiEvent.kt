package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.shell

import com.androidexpert35.audiophilemusicplayer.presentation.screen.common.components.BottomNavDestination

/**
 * User intents emitted from the global app shell (bottom panel).
 */
sealed interface AppShellUiEvent {

    /** Toggle play/pause from the mini-player. */
    data object TogglePlayPause : AppShellUiEvent

    /** Skip to the next track from the mini-player. */
    data object SkipNext : AppShellUiEvent

    /** Skip to the previous track from the mini-player. */
    data object SkipPrevious : AppShellUiEvent

    /** The user tapped the mini-player body to expand the now-playing screen. */
    data object MiniPlayerClicked : AppShellUiEvent

    /**
     * The now-playing overlay was dismissed (swipe-down or back gesture).
     *
     * Causes [AppShellUiModel.isPlayerOpen] to transition to `false`.
     */
    data object ClosePlayer : AppShellUiEvent

    /**
     * A bottom navigation destination was tapped.
     *
     * @property destination The selected [BottomNavDestination].
     */
    data class BottomNavDestinationSelected(
        val destination: BottomNavDestination
    ) : AppShellUiEvent
}

