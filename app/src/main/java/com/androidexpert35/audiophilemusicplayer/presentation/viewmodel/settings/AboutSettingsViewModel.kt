package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings

import androidx.lifecycle.viewModelScope
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ReportBugUseCase
import com.tony.coreui.data.strings.StringResolver
import com.tony.coreui.domain.resource.Resource
import com.tony.coreui.presentation.error.UiErrorMapper
import com.tony.coreui.presentation.navigation.NavigationManager
import com.tony.coreui.presentation.viewmodel.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Coordinates user-initiated session reports from App information.
 * @property reportBugUseCase Prepares diagnostics and opens the email composer.
 */
@HiltViewModel
class AboutSettingsViewModel @Inject constructor(
    private val reportBugUseCase: ReportBugUseCase,
    navigationManager: NavigationManager,
    stringResolver: StringResolver,
    uiErrorMapper: UiErrorMapper,
) : BaseViewModel<AboutSettingsUiModel, AboutSettingsUiEvent, Nothing>(
    navigationManager, stringResolver, uiErrorMapper
) {
    init { setSuccessState(AboutSettingsUiModel()) }

    override fun handleEvent(event: AboutSettingsUiEvent) {
        when (event) {
            AboutSettingsUiEvent.ReportBug -> reportBug()
        }
    }

    private fun reportBug() {
        if (uiState.value.data?.preparingReport == true) return
        setSuccessState(AboutSettingsUiModel(preparingReport = true))
        viewModelScope.launch(exceptionHandler) {
            val result = reportBugUseCase()
            setSuccessState(AboutSettingsUiModel())
            if (result is Resource.Error) handleError(
                errorObject = result,
                retryAction = ::reportBug,
                processUiAfterError = { uiState.value.data }
            )
        }
    }
}
