package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings

/**
 * Immutable UI state for the Settings hub screen.
 *
 * Holds only the small pieces of live state needed to render each category card's
 * one-line status subtitle — the actual settings controls live in the per-category
 * sub-screens and their own ViewModels.
 *
 * @property audiophileEngineEnabled Whether the bit-perfect engine is currently preferred.
 * @property isUsbDacConnected Whether a USB DAC is currently connected.
 * @property musicFolderCount Number of folders the library scan is currently scoped to.
 * @property visibleLibrarySectionCount Number of library sections currently visible.
 * @property totalLibrarySectionCount Total number of library sections that exist.
 * @property clearQueueOnExit Whether the playback queue is cleared when the app is
 *   removed from Recents.
 */
data class SettingsUiModel(
    val audiophileEngineEnabled: Boolean = false,
    val isUsbDacConnected: Boolean = false,
    val musicFolderCount: Int = 0,
    val visibleLibrarySectionCount: Int = 0,
    val totalLibrarySectionCount: Int = 0,
    val clearQueueOnExit: Boolean = false,
)
