package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings

import androidx.lifecycle.viewModelScope
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioTelemetry
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.OutputRouteKind
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.OutputStreamInfo
import com.androidexpert35.audiophilemusicplayer.domain.model.common.toUserMessage
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveAudioTelemetryUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveUsbAudioStatusUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.RefreshUsbAudioDevicesUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.RequestUsbAudioPermissionUseCase
import com.tony.coreui.data.strings.StringResolver
import com.tony.coreui.domain.resource.Resource
import com.tony.coreui.presentation.error.UiErrorMapper
import com.tony.coreui.presentation.navigation.NavigationManager
import com.tony.coreui.presentation.viewmodel.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel powering the USB &amp; DAC settings sub-screen.
 *
 * @property observeUsbAudioStatusUseCase Source of truth for USB DAC state.
 * @property observeAudioTelemetryUseCase Provides real-time audio-pipeline telemetry so
 *   the screen can surface the active USB playback route, which is also an authoritative
 *   fallback when UsbManager discovery is stale but playback is already flowing through
 *   the DAC.
 * @property refreshUsbAudioDevicesUseCase Re-runs USB DAC discovery on demand.
 * @property requestUsbAudioPermissionUseCase Dispatches the USB permission prompt
 *   when a DAC is connected.
 */
@HiltViewModel
class UsbSettingsViewModel @Inject constructor(
    observeUsbAudioStatusUseCase: ObserveUsbAudioStatusUseCase,
    private val observeAudioTelemetryUseCase: ObserveAudioTelemetryUseCase,
    private val refreshUsbAudioDevicesUseCase: RefreshUsbAudioDevicesUseCase,
    private val requestUsbAudioPermissionUseCase: RequestUsbAudioPermissionUseCase,
    navigationManager: NavigationManager,
    stringResolver: StringResolver,
    uiErrorMapper: UiErrorMapper
) : BaseViewModel<UsbSettingsUiModel, UsbSettingsUiEvent, UsbSettingsUiEffect>(
    navigationManager = navigationManager,
    stringResolver = stringResolver,
    uiErrorMapper = uiErrorMapper
) {

    init {
        setSuccessState(UsbSettingsUiModel())

        observeUsbAudioStatusUseCase()
            .onEach { usbStatus ->
                val current = uiState.value.data ?: UsbSettingsUiModel()
                setSuccessState(current.copy(usbAudioStatus = usbStatus))
            }
            .launchIn(viewModelScope)

        observeAudioTelemetryUseCase()
            .onEach { telemetry ->
                val current = uiState.value.data ?: return@onEach
                val isUsbPlaybackActive = telemetry.isUsbPlaybackActive()
                setSuccessState(
                    current.copy(
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

    override fun handleEvent(event: UsbSettingsUiEvent) {
        when (event) {
            UsbSettingsUiEvent.RefreshUsbAudioDevices -> handleRefreshUsbDevices()
            UsbSettingsUiEvent.RequestUsbAudioPermission -> handleRequestUsbPermission()
        }
    }

    private fun handleRefreshUsbDevices() {
        val currentModel = uiState.value.data ?: UsbSettingsUiModel()
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
            UsbSettingsUiEffect.ToggleError(
                result.data?.toUserMessage()
                    ?: resolveString(R.string.settings_audiophile_toggle_error_fallback)
            )
        )
    }
}
