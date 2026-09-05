package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.onboarding

import androidx.compose.runtime.Immutable

/**
 * Immutable UI model for the onboarding screen.
 *
 * @property state Current onboarding step rendered by the Compose screen.
 * @property preparingReport Prevents duplicate drafts while diagnostics are prepared.
 * @property reportFailure Actionable feedback if report preparation or email launch failed.
 */
@Immutable
data class OnboardingUiModel(
    val state: OnboardingState = OnboardingState.RequiresPermission,
    val preparingReport: Boolean = false,
    val reportFailure: String? = null
)

