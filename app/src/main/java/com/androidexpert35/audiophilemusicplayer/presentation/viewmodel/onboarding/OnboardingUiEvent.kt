package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.onboarding

/**
 * User intents emitted from the onboarding screen.
 */
sealed interface OnboardingUiEvent {

    /**
     * Signals that the screen has been composed and permission state is known.
     *
     * @property hasMediaPermission Whether the required media permission is already granted.
     */
    data class Initialize(val hasMediaPermission: Boolean) : OnboardingUiEvent

    /** Requests that the UI launch the platform permission dialog. */
    data object RequestPermissionTapped : OnboardingUiEvent

    /**
     * Delivers the result from the platform permission dialog.
     *
     * @property granted Whether the permission request succeeded.
     */
    data class PermissionResult(val granted: Boolean) : OnboardingUiEvent

    /** Requests that the UI launch the system folder chooser. */
    data object AddMusicFolderTapped : OnboardingUiEvent

    /**
     * Delivers the result from the system folder chooser.
     *
     * @property folderId Identifier of the chosen folder, or `null` when the user
     *   dismissed the chooser without picking one.
     */
    data class MusicFolderPicked(val folderId: String?) : OnboardingUiEvent

    /** Retries the scan-and-index flow after an indexing failure. */
    data object RetryIndexing : OnboardingUiEvent
    /** Prepares a diagnostic attachment and opens an email draft for user review. */
    data object ReportBug : OnboardingUiEvent
}

