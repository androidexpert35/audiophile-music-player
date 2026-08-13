package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings

import androidx.lifecycle.viewModelScope
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioTelemetry
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.OutputRouteKind
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.OutputStreamInfo
import com.androidexpert35.audiophilemusicplayer.domain.model.common.toUserMessage
import com.androidexpert35.audiophilemusicplayer.domain.usecase.AddMusicFolderUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveAudioTelemetryUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveAudiophileEngineEnabledUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveClearQueueOnExitUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveHiResRemasterEnabledUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveMusicFoldersUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveSueEnabledUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveUsbAudioStatusUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.RefreshUsbAudioDevicesUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.RemoveMusicFolderUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.RequestUsbAudioPermissionUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.SetAudiophileEngineEnabledUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.SetClearQueueOnExitUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.SetHiResRemasterEnabledUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.SetSueEnabledUseCase
import com.androidexpert35.audiophilemusicplayer.presentation.navigation.AppRoutes
import com.tony.coreui.data.strings.StringResolver
import com.tony.coreui.domain.resource.Resource
import com.tony.coreui.presentation.error.UiErrorMapper
import com.tony.coreui.presentation.navigation.NavigationManager
import com.tony.coreui.presentation.viewmodel.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel powering the Settings screen.
 *
 * Observes the persisted "audiophile engine enabled" preference and pushes
 * updates through the associated use case. The actual engine hot-swap is
 * performed by
 * [com.androidexpert35.audiophilemusicplayer.data.playback.engine.AudioEngineSettingsCoordinator]
 * which reacts to the same preference flow — keeping Presentation purely
 * declarative.
 *
 * @property observeAudiophileEngineEnabledUseCase Source of truth observer.
 * @property observeUsbAudioStatusUseCase Source of truth for USB DAC state.
 * @property observeSueEnabledUseCase Source of truth for the SUE toggle.
 * @property observeHiResRemasterEnabledUseCase Source of truth for the Hi-Res
 *   Dynamic Remaster toggle.
 * @property observeClearQueueOnExitUseCase Source of truth for the queue-retention toggle.
 * @property observeAudioTelemetryUseCase Provides real-time audio-pipeline
 *   telemetry so the Settings screen can surface the active SUE state.
 * @property setAudiophileEngineEnabledUseCase Writes the new preference value.
 * @property setSueEnabledUseCase Writes the SUE preference.
 * @property setHiResRemasterEnabledUseCase Writes the Hi-Res Remaster preference.
 * @property setClearQueueOnExitUseCase Writes the queue-retention preference.
 * @property refreshUsbAudioDevicesUseCase Re-runs USB DAC discovery on demand.
 * @property requestUsbAudioPermissionUseCase Dispatches the USB permission
 *   prompt when a DAC is connected.
 * @property observeMusicFoldersUseCase Source of truth for the folders the library is
 *   scanned from.
 * @property addMusicFolderUseCase Brings a folder chosen here into the library scan
 *   scope, so the user is not limited to the folder they picked during onboarding.
 * @property removeMusicFolderUseCase Drops a folder from the library scan scope.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val observeAudiophileEngineEnabledUseCase: ObserveAudiophileEngineEnabledUseCase,
    private val observeUsbAudioStatusUseCase: ObserveUsbAudioStatusUseCase,
    observeSueEnabledUseCase: ObserveSueEnabledUseCase,
    observeHiResRemasterEnabledUseCase: ObserveHiResRemasterEnabledUseCase,
    observeClearQueueOnExitUseCase: ObserveClearQueueOnExitUseCase,
    private val observeAudioTelemetryUseCase: ObserveAudioTelemetryUseCase,
    private val setAudiophileEngineEnabledUseCase: SetAudiophileEngineEnabledUseCase,
    private val setSueEnabledUseCase: SetSueEnabledUseCase,
    private val setHiResRemasterEnabledUseCase: SetHiResRemasterEnabledUseCase,
    private val setClearQueueOnExitUseCase: SetClearQueueOnExitUseCase,
    private val refreshUsbAudioDevicesUseCase: RefreshUsbAudioDevicesUseCase,
    private val requestUsbAudioPermissionUseCase: RequestUsbAudioPermissionUseCase,
    observeMusicFoldersUseCase: ObserveMusicFoldersUseCase,
    private val addMusicFolderUseCase: AddMusicFolderUseCase,
    private val removeMusicFolderUseCase: RemoveMusicFolderUseCase,
    navigationManager: NavigationManager,
    stringResolver: StringResolver,
    uiErrorMapper: UiErrorMapper
) : BaseViewModel<SettingsUiModel, SettingsUiEvent, SettingsUiEffect>(
    navigationManager = navigationManager,
    stringResolver = stringResolver,
    uiErrorMapper = uiErrorMapper
) {

    init {
        setSuccessState(SettingsUiModel())
        combine(
            observeAudiophileEngineEnabledUseCase(),
            observeUsbAudioStatusUseCase(),
            observeSueEnabledUseCase(),
        ) { enabled, usbStatus, sueEnabled ->
            val current = uiState.value.data ?: SettingsUiModel()
            current.copy(
                audiophileEngineEnabled = enabled,
                usbAudioStatus = usbStatus,
                sueEnabled = sueEnabled,
            )
        }
            .onEach { model -> setSuccessState(model) }
            .launchIn(viewModelScope)

        // Observe the Hi-Res Remaster preference separately so the main combine
        // above does not need to be restructured.
        observeHiResRemasterEnabledUseCase()
            .onEach { hiResEnabled ->
                val current = uiState.value.data ?: return@onEach
                setSuccessState(current.copy(hiResRemasterEnabled = hiResEnabled))
            }
            .launchIn(viewModelScope)

        observeClearQueueOnExitUseCase()
            .onEach { clearQueueOnExit ->
                val current = uiState.value.data ?: return@onEach
                setSuccessState(current.copy(clearQueueOnExit = clearQueueOnExit))
            }
            .launchIn(viewModelScope)

        // Observe the granted music folders separately so the list stays live while the
        // user adds or removes folders without leaving the screen.
        observeMusicFoldersUseCase()
            .onEach { folders ->
                val current = uiState.value.data ?: return@onEach
                setSuccessState(current.copy(musicFolders = folders))
            }
            .launchIn(viewModelScope)

        // Observe telemetry separately so live pipeline state does not block on
        // persisted settings. The runtime USB route is also an authoritative
        // fallback when UsbManager discovery is stale but playback is already
        // flowing through the DAC.
        observeAudioTelemetryUseCase()
            .onEach { telemetry ->
                val current = uiState.value.data ?: return@onEach
                val isUsbPlaybackActive = telemetry.isUsbPlaybackActive()
                setSuccessState(
                    current.copy(
                        sueStatus = telemetry.sueStatus,
                        isUsbPlaybackActive = isUsbPlaybackActive,
                        activeUsbPlaybackDeviceName = telemetry.bitPerfectDiagnostics
                            ?.activeDeviceName
                            ?.takeIf { isUsbPlaybackActive },
                    )
                )
            }
            .launchIn(viewModelScope)
    }

    private fun AudioTelemetry.isUsbPlaybackActive(): Boolean =
        isAudiophileEngineActive &&
            streamInfo !is OutputStreamInfo.Unknown &&
            bitPerfectDiagnostics?.outputRouteKind == OutputRouteKind.USB

    override fun handleEvent(event: SettingsUiEvent) {
        when (event) {
            is SettingsUiEvent.SetClearQueueOnExit -> handleSetClearQueueOnExit(event.enabled)
            is SettingsUiEvent.SetAudiophileEngineEnabled -> handleSetAudiophileEngineEnabled(event.enabled)
            is SettingsUiEvent.SetSueEnabled -> handleSetSueEnabled(event.enabled)
            is SettingsUiEvent.SetHiResRemasterEnabled -> handleSetHiResRemasterEnabled(event.enabled)
            SettingsUiEvent.RefreshUsbAudioDevices -> handleRefreshUsbDevices()
            SettingsUiEvent.RequestUsbAudioPermission -> handleRequestUsbPermission()
            SettingsUiEvent.AddMusicFolderTapped -> emitEffect(SettingsUiEffect.PickMusicFolder)
            is SettingsUiEvent.MusicFolderPicked -> handleMusicFolderPicked(event.folderId)
            is SettingsUiEvent.RemoveMusicFolder -> handleRemoveMusicFolder(event.folderId)
        }
    }

    /** Persists whether task removal from recents should also discard the active queue. */
    private fun handleSetClearQueueOnExit(enabled: Boolean) {
        if (uiState.value.data?.clearQueueOnExit == enabled) return

        viewModelScope.launch {
            when (val result = setClearQueueOnExitUseCase(enabled)) {
                is Resource.Success -> {
                    val current = uiState.value.data ?: return@launch
                    setSuccessState(current.copy(clearQueueOnExit = enabled))
                }

                is Resource.Error -> emitError(result)
            }
        }
    }

    /**
     * Persists a folder chosen in the system chooser, then opens the same indexing screen
     * shown during onboarding so the resulting rescan is visible rather than silent.
     *
     * [com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.onboarding.OnboardingViewModel]
     * re-evaluates its own state on that screen: permission and folder steps are already
     * satisfied at this point, so it goes straight into
     * [com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.onboarding.OnboardingState.Scanning]
     * and returns to the home flow when the scan it drives completes.
     *
     * @param folderId Chosen folder identifier, or `null` when the chooser was dismissed.
     */
    private fun handleMusicFolderPicked(folderId: String?) {
        if (folderId.isNullOrBlank()) return

        viewModelScope.launch {
            when (val result = addMusicFolderUseCase(folderId)) {
                is Resource.Success -> navigateToRoute(AppRoutes.Onboarding.route)
                is Resource.Error -> emitError(result)
            }
        }
    }

    /**
     * Drops a folder from the library scan scope, then opens the same indexing screen used
     * for adding one so the listener sees the remaining library being rebuilt rather than
     * having it happen silently in the background.
     *
     * @param folderId Identifier of the folder to stop scanning.
     */
    private fun handleRemoveMusicFolder(folderId: String) {
        viewModelScope.launch {
            when (val result = removeMusicFolderUseCase(folderId)) {
                is Resource.Success -> navigateToRoute(AppRoutes.Onboarding.route)
                is Resource.Error -> emitError(result)
            }
        }
    }

    private fun handleSetAudiophileEngineEnabled(enabled: Boolean) {
        val currentModel = uiState.value.data ?: SettingsUiModel()
        if (currentModel.isAudiophileEngineSwitchInProgress ||
            currentModel.audiophileEngineEnabled == enabled
        ) {
            return
        }

        viewModelScope.launch {
            setSuccessState(currentModel.copy(isAudiophileEngineSwitchInProgress = true))

            when (val result = setAudiophileEngineEnabledUseCase(enabled)) {
                is Resource.Success -> {
                    setSuccessState(
                        (uiState.value.data ?: currentModel).copy(
                            audiophileEngineEnabled = enabled,
                            isAudiophileEngineSwitchInProgress = false,
                        )
                    )
                }

                is Resource.Error -> {
                    setSuccessState(
                        (uiState.value.data ?: currentModel).copy(
                            isAudiophileEngineSwitchInProgress = false,
                        )
                    )
                    emitEffect(
                        SettingsUiEffect.ToggleError(
                            result.data?.toUserMessage()
                                ?: resolveString(R.string.settings_audiophile_toggle_error_fallback)
                        )
                    )
                }
            }
        }
    }

    private fun handleSetSueEnabled(enabled: Boolean) {
        val currentModel = uiState.value.data ?: SettingsUiModel()
        if (currentModel.sueEnabled == enabled) return

        viewModelScope.launch {
            when (val result = setSueEnabledUseCase(enabled)) {
                is Resource.Success -> {
                    setSuccessState(
                        (uiState.value.data ?: currentModel).copy(sueEnabled = enabled)
                    )
                }

                is Resource.Error -> emitError(result)
            }
        }
    }

    private fun handleSetHiResRemasterEnabled(enabled: Boolean) {
        val currentModel = uiState.value.data ?: SettingsUiModel()
        if (currentModel.hiResRemasterEnabled == enabled) return

        viewModelScope.launch {
            when (val result = setHiResRemasterEnabledUseCase(enabled)) {
                is Resource.Success -> {
                    setSuccessState(
                        (uiState.value.data ?: currentModel).copy(hiResRemasterEnabled = enabled)
                    )
                }


                is Resource.Error -> emitError(result)
            }
        }
    }

    private fun handleRefreshUsbDevices() {
        val currentModel = uiState.value.data ?: SettingsUiModel()
        if (currentModel.isUsbDeviceRefreshInProgress) return

        viewModelScope.launch {
            setSuccessState(currentModel.copy(isUsbDeviceRefreshInProgress = true))

            when (val result = refreshUsbAudioDevicesUseCase()) {
                is Resource.Success -> {
                    setSuccessState(
                        (uiState.value.data ?: currentModel).copy(
                            isUsbDeviceRefreshInProgress = false,
                        )
                    )
                }

                is Resource.Error -> {
                    setSuccessState(
                        (uiState.value.data ?: currentModel).copy(
                            isUsbDeviceRefreshInProgress = false,
                        )
                    )
                    emitError(result)
                }
            }
        }
    }

    private fun handleRequestUsbPermission() {
        viewModelScope.launch {
            when (val result = requestUsbAudioPermissionUseCase()) {
                is Resource.Success -> Unit
                is Resource.Error -> emitError(result)
            }
        }
    }

    private fun emitError(result: Resource.Error) {
        emitEffect(
            SettingsUiEffect.ToggleError(
                result.data?.toUserMessage()
                    ?: resolveString(R.string.settings_audiophile_toggle_error_fallback)
            )
        )
    }
}
