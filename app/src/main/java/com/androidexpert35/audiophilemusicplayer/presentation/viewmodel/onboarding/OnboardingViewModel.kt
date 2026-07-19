package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.onboarding

import androidx.lifecycle.viewModelScope
import com.androidexpert35.audiophilemusicplayer.domain.usecase.IsMediaLibraryIndexedUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ScanAndIndexMediaUseCase
import com.tony.coreui.data.strings.StringResolver
import com.tony.coreui.domain.resource.Resource
import com.tony.coreui.presentation.error.UiErrorMapper
import com.tony.coreui.presentation.navigation.NavigationManager
import com.tony.coreui.presentation.viewmodel.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel coordinating the initial permission and media-indexing onboarding flow.
 *
 * It keeps the screen fully state-driven: permission prompts are emitted as one-shot effects,
 * indexing progress is exposed as immutable state, and completion triggers a navigation effect.
 *
 * @property scanAndIndexMediaUseCase Executes the MediaStore-to-Room indexing workflow.
 * @property isMediaLibraryIndexedUseCase Checks whether onboarding can be skipped on launch.
 * @property navigationManager Shared navigation manager required by the base ViewModel contract.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val scanAndIndexMediaUseCase: ScanAndIndexMediaUseCase,
    private val isMediaLibraryIndexedUseCase: IsMediaLibraryIndexedUseCase,
    navigationManager: NavigationManager,
    stringResolver: StringResolver,
    uiErrorMapper: UiErrorMapper
) : BaseViewModel<OnboardingUiModel, OnboardingUiEvent, OnboardingUiEffect>(
    navigationManager = navigationManager,
    stringResolver = stringResolver,
    uiErrorMapper = uiErrorMapper
) {

    private var hasInitialized: Boolean = false

    init {
        setSuccessState(OnboardingUiModel())
    }

    override fun handleEvent(event: OnboardingUiEvent) {
        when (event) {
            is OnboardingUiEvent.Initialize -> initialize(event.hasMediaPermission)
            OnboardingUiEvent.RequestPermissionTapped -> emitEffect(OnboardingUiEffect.RequestPermission)
            is OnboardingUiEvent.PermissionResult -> handlePermissionResult(event.granted)
            OnboardingUiEvent.RetryIndexing -> startIndexing()
        }
    }

    /**
     * Processes the initial permission state once when the screen first appears.
     *
     * @param hasMediaPermission Whether the app already has media-library permission.
     */
    private fun initialize(hasMediaPermission: Boolean) {
        if (hasInitialized) return
        hasInitialized = true

        if (!hasMediaPermission) {
            setSuccessState(OnboardingUiModel(OnboardingState.RequiresPermission))
            return
        }

        viewModelScope.launch(exceptionHandler) {
            if (isMediaLibraryIndexedUseCase()) {
                completeOnboarding()
            } else {
                startIndexing()
            }
        }
    }

    /**
     * Reacts to the result of the runtime permission dialog.
     *
     * @param granted Whether the user granted library access.
     */
    private fun handlePermissionResult(granted: Boolean) {
        if (!granted) {
            setSuccessState(OnboardingUiModel(OnboardingState.RequiresPermission))
            return
        }

        startIndexing()
    }

    /**
     * Starts indexing the local library and publishes progress as immutable UI state.
     */
    private fun startIndexing() {
        val currentState = uiState.value.data?.state
        if (currentState is OnboardingState.Scanning) return

        setSuccessState(
            OnboardingUiModel(
                OnboardingState.Scanning(
                    progress = 0f,
                    currentFile = ""
                )
            )
        )

        viewModelScope.launch(exceptionHandler) {
            var indexingFailed = false

            scanAndIndexMediaUseCase().collect { resource ->
                when (resource) {
                    is Resource.Success -> {
                        setSuccessState(
                            OnboardingUiModel(
                                OnboardingState.Scanning(
                                    progress = resource.data.progress.coerceIn(0f, 1f),
                                    currentFile = resource.data.currentFile
                                )
                            )
                        )
                    }

                    is Resource.Error -> {
                        indexingFailed = true
                        handleError(
                            errorObject = resource,
                            retryAction = ::startIndexing,
                            processUiAfterError = { uiState.value.data }
                        )
                    }
                }
            }

            if (!indexingFailed) {
                completeOnboarding()
            }
        }
    }

    /** Marks the flow as completed and asks the host to navigate into the main app graph. */
    private fun completeOnboarding() {
        setSuccessState(OnboardingUiModel(OnboardingState.Completed))
        emitEffect(OnboardingUiEffect.NavigateToHome)
    }
}


