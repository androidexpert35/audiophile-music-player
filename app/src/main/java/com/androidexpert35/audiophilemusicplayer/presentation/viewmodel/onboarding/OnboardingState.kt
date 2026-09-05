package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.onboarding

/**
 * Represents the current step of the initial onboarding and media-indexing journey.
 */
sealed interface OnboardingState {

    /** Indicates that the app still needs media-library permission from the user. */
    data object RequiresPermission : OnboardingState

    /**
     * Indicates that the user has not yet chosen where their music lives.
     *
     * The library is built only from folders the user picks, so this step is required
     * rather than optional: it is what keeps unrelated audio out of the catalogue and
     * the only way DSD files become readable at all.
     *
     * @property hasFailedAttempt Whether a previous pick was cancelled or rejected, so
     *   the screen can explain why the app is asking again.
     * @property errorMessage Persistent explanation when the selected folder was rejected.
     */
    data class RequiresMusicFolder(
        val hasFailedAttempt: Boolean = false,
        val errorMessage: String? = null
    ) : OnboardingState

    /**
     * Keeps a failed scan actionable without requiring another folder grant.
     *
     * @property message Explanation retained until the user retries or adds a folder.
     */
    data class IndexingFailed(val message: String) : OnboardingState

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

