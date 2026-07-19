package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.onboarding

import androidx.compose.runtime.Immutable

/**
 * Immutable UI model for the onboarding screen.
 *
 * @property state Current onboarding step rendered by the Compose screen.
 */
@Immutable
data class OnboardingUiModel(
    val state: OnboardingState = OnboardingState.RequiresPermission
)

