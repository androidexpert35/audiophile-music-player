package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings

import androidx.lifecycle.viewModelScope
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.domain.model.common.toUserMessage
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveAudioTelemetryUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveAudiophileEngineEnabledUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveHiResRemasterEnabledUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveSueEnabledUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.SetAudiophileEngineEnabledUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.SetHiResRemasterEnabledUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.SetSueEnabledUseCase
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
 * ViewModel powering the Audio Engine &amp; DSP settings sub-screen.
 *
 * Observes the persisted "audiophile engine enabled" preference and pushes updates
 * through the associated use case. The actual engine hot-swap is performed by
 * [com.androidexpert35.audiophilemusicplayer.data.playback.engine.AudioEngineSettingsCoordinator]
 * which reacts to the same preference flow — keeping Presentation purely declarative.
 *
 * @property observeAudiophileEngineEnabledUseCase Source of truth observer.
 * @property observeSueEnabledUseCase Source of truth for the SUE toggle.
 * @property observeHiResRemasterEnabledUseCase Source of truth for the Hi-Res
 *   Dynamic Remaster toggle.
 * @property observeAudioTelemetryUseCase Provides real-time audio-pipeline telemetry
 *   so the screen can surface the active SUE state.
 * @property setAudiophileEngineEnabledUseCase Writes the new preference value.
 * @property setSueEnabledUseCase Writes the SUE preference.
 * @property setHiResRemasterEnabledUseCase Writes the Hi-Res Remaster preference.
 */
@HiltViewModel
class AudioEngineSettingsViewModel @Inject constructor(
    private val observeAudiophileEngineEnabledUseCase: ObserveAudiophileEngineEnabledUseCase,
    observeSueEnabledUseCase: ObserveSueEnabledUseCase,
    observeHiResRemasterEnabledUseCase: ObserveHiResRemasterEnabledUseCase,
    private val observeAudioTelemetryUseCase: ObserveAudioTelemetryUseCase,
    private val setAudiophileEngineEnabledUseCase: SetAudiophileEngineEnabledUseCase,
    private val setSueEnabledUseCase: SetSueEnabledUseCase,
    private val setHiResRemasterEnabledUseCase: SetHiResRemasterEnabledUseCase,
    navigationManager: NavigationManager,
    stringResolver: StringResolver,
    uiErrorMapper: UiErrorMapper
) : BaseViewModel<AudioEngineSettingsUiModel, AudioEngineSettingsUiEvent, AudioEngineSettingsUiEffect>(
    navigationManager = navigationManager,
    stringResolver = stringResolver,
    uiErrorMapper = uiErrorMapper
) {

    init {
        setSuccessState(AudioEngineSettingsUiModel())
        combine(
            observeAudiophileEngineEnabledUseCase(),
            observeSueEnabledUseCase(),
        ) { enabled, sueEnabled ->
            val current = uiState.value.data ?: AudioEngineSettingsUiModel()
            current.copy(
                audiophileEngineEnabled = enabled,
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

        // Observe telemetry separately so live pipeline state does not block on
        // persisted settings.
        observeAudioTelemetryUseCase()
            .onEach { telemetry ->
                val current = uiState.value.data ?: return@onEach
                setSuccessState(current.copy(sueStatus = telemetry.sueStatus))
            }
            .launchIn(viewModelScope)
    }

    override fun handleEvent(event: AudioEngineSettingsUiEvent) {
        when (event) {
            is AudioEngineSettingsUiEvent.SetAudiophileEngineEnabled ->
                handleSetAudiophileEngineEnabled(event.enabled)
            is AudioEngineSettingsUiEvent.SetSueEnabled -> handleSetSueEnabled(event.enabled)
            is AudioEngineSettingsUiEvent.SetHiResRemasterEnabled ->
                handleSetHiResRemasterEnabled(event.enabled)
        }
    }

    private fun handleSetAudiophileEngineEnabled(enabled: Boolean) {
        val currentModel = uiState.value.data ?: AudioEngineSettingsUiModel()
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
                        AudioEngineSettingsUiEffect.ToggleError(
                            result.data?.toUserMessage()
                                ?: resolveString(R.string.settings_audiophile_toggle_error_fallback)
                        )
                    )
                }
            }
        }
    }

    private fun handleSetSueEnabled(enabled: Boolean) {
        val currentModel = uiState.value.data ?: AudioEngineSettingsUiModel()
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
        val currentModel = uiState.value.data ?: AudioEngineSettingsUiModel()
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

    private fun emitError(result: Resource.Error) {
        emitEffect(
            AudioEngineSettingsUiEffect.ToggleError(
                result.data?.toUserMessage()
                    ?: resolveString(R.string.settings_audiophile_toggle_error_fallback)
            )
        )
    }
}
