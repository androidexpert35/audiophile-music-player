package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.shell

import androidx.compose.runtime.Immutable
import com.androidexpert35.audiophilemusicplayer.domain.model.playback.PlaybackState

/**
 * Immutable UI state for the global app shell.
 *
 * Drives the floating bottom panel (mini-player + navigation bar) that
 * persists across all screens, and controls the full-screen player overlay
 * that sits above the NavHost so the underlying library stays composed.
 *
 * @property playbackState Current playback snapshot used by the mini-player.
 * @property currentRoute The active navigation route, used to highlight the
 *   correct bottom-nav item and to decide mini-player visibility.
 * @property isPlayerOpen Whether the full-screen now-playing overlay is visible.
 *   Managed exclusively by the shell ViewModel — never driven by navigation.
 */
@Immutable
data class AppShellUiModel(
    val playbackState: PlaybackState = PlaybackState.IDLE,
    val currentRoute: String? = null,
    val isPlayerOpen: Boolean = false
)

