package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.onboarding

/**
 * Represents the current step of the initial onboarding and media-indexing journey.
 */
sealed interface OnboardingState {

    /** Indicates that the app still needs media-library permission from the user. */
    data object RequiresPermission : OnboardingState

    /**
     * Indicates that the app is currently indexing local audio files.
     *
     * @property progress Normalized progress in the inclusive range `0f..1f`.
     * @property currentFile Display-friendly name of the file currently being indexed.
     */
    data class Scanning(
        val progress: Float,
        val currentFile: String
    ) : OnboardingState

    /** Indicates that indexing finished and the app can continue to the home flow. */
    data object Completed : OnboardingState
}

