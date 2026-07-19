package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.onboarding

/**
 * One-shot side effects emitted from the onboarding ViewModel.
 */
sealed interface OnboardingUiEffect {

    /** Requests that the Compose layer open the system media-permission dialog. */
    data object RequestPermission : OnboardingUiEffect

    /** Indicates that onboarding finished and the host should navigate to the home flow. */
    data object NavigateToHome : OnboardingUiEffect
}

