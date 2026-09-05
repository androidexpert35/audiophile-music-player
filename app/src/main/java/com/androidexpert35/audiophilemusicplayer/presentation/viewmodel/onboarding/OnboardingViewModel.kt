package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.onboarding

import androidx.lifecycle.viewModelScope
import com.androidexpert35.audiophilemusicplayer.domain.model.common.LibraryResourceError
import com.androidexpert35.audiophilemusicplayer.domain.usecase.AddMusicFolderUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.HasMusicFoldersUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.IsMediaLibraryIndexedUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ReportBugUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ScanAndIndexMediaUseCase
import com.tony.coreui.data.strings.StringResolver
import com.tony.coreui.domain.resource.Resource
import com.tony.coreui.presentation.error.UiErrorMapper
import com.tony.coreui.presentation.navigation.NavigationManager
import com.tony.coreui.presentation.viewmodel.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel coordinating the initial permission, folder-selection, and media-indexing
 * onboarding flow.
 *
 * It keeps the screen fully state-driven: permission and folder prompts are emitted as
 * one-shot effects, indexing progress is exposed as immutable state, and completion
 * triggers a navigation effect.
 *
 * The folder step is deliberately mandatory. The library is indexed only from folders
 * the user names, which is both what keeps unrelated audio (messenger voice notes) out
 * of the catalogue and the only mechanism that makes DSD files readable — Android grants
 * no access to `.dsf` / `.dff` through the audio permission alone.
 *
 * @property scanAndIndexMediaUseCase Executes the scan-to-Room indexing workflow.
 * @property isMediaLibraryIndexedUseCase Checks whether onboarding can be skipped on launch.
 * @property hasMusicFoldersUseCase Checks whether the user already named their music folders.
 * @property addMusicFolderUseCase Persists the folder chosen in the system chooser.
 * @property reportBugUseCase Opens a diagnostic email draft at the user's request.
 * @property navigationManager Shared navigation manager required by the base ViewModel contract.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val scanAndIndexMediaUseCase: ScanAndIndexMediaUseCase,
    private val isMediaLibraryIndexedUseCase: IsMediaLibraryIndexedUseCase,
    private val hasMusicFoldersUseCase: HasMusicFoldersUseCase,
    private val addMusicFolderUseCase: AddMusicFolderUseCase,
    private val reportBugUseCase: ReportBugUseCase,
    navigationManager: NavigationManager,
    stringResolver: StringResolver,
    uiErrorMapper: UiErrorMapper
) : BaseViewModel<OnboardingUiModel, OnboardingUiEvent, OnboardingUiEffect>(
    navigationManager = navigationManager,
    stringResolver = stringResolver,
    uiErrorMapper = uiErrorMapper
) {

    private var hasInitialized: Boolean = false
    private val onboardingErrorMapper = uiErrorMapper
    private var currentFailure: LibraryResourceError? = null

    init {
        setSuccessState(OnboardingUiModel())
    }

    override fun handleEvent(event: OnboardingUiEvent) {
        when (event) {
            is OnboardingUiEvent.Initialize -> initialize(event.hasMediaPermission)
            OnboardingUiEvent.RequestPermissionTapped -> emitEffect(OnboardingUiEffect.RequestPermission)
            is OnboardingUiEvent.PermissionResult -> handlePermissionResult(event.granted)
            OnboardingUiEvent.AddMusicFolderTapped -> emitEffect(OnboardingUiEffect.PickMusicFolder)
            is OnboardingUiEvent.MusicFolderPicked -> handleMusicFolderPicked(event.folderId)
            OnboardingUiEvent.RetryIndexing -> startIndexing()
            OnboardingUiEvent.ReportBug -> reportBug()
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

        resumeAfterPermission()
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

        resumeAfterPermission()
    }

    /**
     * Decides the next step once media permission is in place.
     *
     * A cached index is only trusted when the user has also named at least one music
     * folder. Anything indexed before folders existed came from a whole-device scan, so
     * it is re-built rather than shown as-is.
     */
    private fun resumeAfterPermission() {
        viewModelScope.launch(exceptionHandler) {
            when {
                !hasMusicFoldersUseCase() ->
                    setSuccessState(OnboardingUiModel(OnboardingState.RequiresMusicFolder()))

                isMediaLibraryIndexedUseCase() -> completeOnboarding()

                else -> startIndexing()
            }
        }
    }

    /**
     * Persists the folder chosen in the system chooser and moves straight into indexing.
     *
     * @param folderId Chosen folder identifier, or `null` when the chooser was dismissed.
     */
    private fun handleMusicFolderPicked(folderId: String?) {
        if (folderId.isNullOrBlank()) {
            if (uiState.value.data?.state is OnboardingState.IndexingFailed) return
            setSuccessState(
                OnboardingUiModel(OnboardingState.RequiresMusicFolder(hasFailedAttempt = true))
            )
            return
        }

        viewModelScope.launch(exceptionHandler) {
            when (val result = addMusicFolderUseCase(folderId)) {
                is Resource.Success -> startIndexing()

                is Resource.Error -> {
                    val failure = result.data as? LibraryResourceError ?: LibraryResourceError.FOLDER_FAILED
                    setSuccessState(
                        OnboardingUiModel(
                            OnboardingState.RequiresMusicFolder(
                                hasFailedAttempt = true,
                                errorMessage = onboardingErrorMapper.mapResourceError(failure).message
                            )
                        )
                    )
                    showFailure(failure) { handleMusicFolderPicked(folderId) }
                }
            }
        }
    }

    /**
     * Starts indexing the local library and publishes progress as immutable UI state.
     */
    private fun startIndexing() {
        val currentState = uiState.value.data?.state
        if (currentState is OnboardingState.Scanning) return
        currentFailure = null

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

            scanAndIndexMediaUseCase().catch {
                emit(Resource.Error(LibraryResourceError.SCAN_FAILED))
            }.collect { resource ->
                if (indexingFailed) return@collect
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
                        // Preserve the reason in the screen itself, including after a dialog
                        // dismissal. A storage or database failure does not require a new grant.
                        setSuccessState(
                            OnboardingUiModel(
                                OnboardingState.IndexingFailed(
                                    onboardingErrorMapper.mapResourceError(
                                        resource.data as? LibraryResourceError ?: LibraryResourceError.SCAN_FAILED
                                    ).message,
                                    canRetry = (resource.data as? LibraryResourceError)?.isRecoverable == true
                                )
                            )
                        )
                        showFailure(
                            resource.data as? LibraryResourceError ?: LibraryResourceError.SCAN_FAILED,
                            ::startIndexing
                        )
                    }
                }
            }

            if (!indexingFailed) {
                completeOnboarding()
            }
        }
    }

    private fun showFailure(failure: LibraryResourceError, retry: () -> Unit) {
        currentFailure = failure
        handleError(
            errorObject = failure,
            retryAction = retry.takeIf { failure.isRecoverable },
            processUiAfterError = { uiState.value.data }
        )
    }

    private fun reportBug() {
        val failure = currentFailure ?: return
        val model = uiState.value.data ?: return
        if (model.preparingReport) return
        updateUiData(model.copy(preparingReport = true, reportFailure = null))
        viewModelScope.launch(exceptionHandler) {
            val result = reportBugUseCase(failure)
            uiState.value.data?.let {
                updateUiData(it.copy(
                    preparingReport = false,
                    reportFailure = (result as? Resource.Error)?.let { error ->
                        onboardingErrorMapper.mapResourceError(error.data).message
                    }
                ))
            }
        }
    }

    /** Marks the flow as completed and asks the host to navigate into the main app graph. */
    private fun completeOnboarding() {
        setSuccessState(OnboardingUiModel(OnboardingState.Completed))
        emitEffect(OnboardingUiEffect.NavigateToHome)
    }
}
