package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings

/**
 * One-shot effects emitted from the Settings hub ViewModel.
 *
 * The hub only navigates, which [com.tony.coreui.presentation.viewmodel.BaseViewModel]
 * already handles internally — there is currently nothing left for the hub to signal
 * through a one-shot effect.
 */
sealed interface SettingsUiEffect
