package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings

import androidx.lifecycle.viewModelScope
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.domain.model.common.toUserMessage
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveClearQueueOnExitUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.SetClearQueueOnExitUseCase
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
 * ViewModel powering the Playback Behavior settings sub-screen.
 *
 * @property observeClearQueueOnExitUseCase Source of truth for the queue-retention toggle.
 * @property setClearQueueOnExitUseCase Writes the queue-retention preference.
 */
@HiltViewModel
class PlaybackBehaviorSettingsViewModel @Inject constructor(
    observeClearQueueOnExitUseCase: ObserveClearQueueOnExitUseCase,
    private val setClearQueueOnExitUseCase: SetClearQueueOnExitUseCase,
    navigationManager: NavigationManager,
    stringResolver: StringResolver,
    uiErrorMapper: UiErrorMapper
) : BaseViewModel<PlaybackBehaviorSettingsUiModel, PlaybackBehaviorSettingsUiEvent, PlaybackBehaviorSettingsUiEffect>(
    navigationManager = navigationManager,
    stringResolver = stringResolver,
    uiErrorMapper = uiErrorMapper
) {

    init {
        setSuccessState(PlaybackBehaviorSettingsUiModel())
        observeClearQueueOnExitUseCase()
            .onEach { clearQueueOnExit ->
                val current = uiState.value.data ?: PlaybackBehaviorSettingsUiModel()
                setSuccessState(current.copy(clearQueueOnExit = clearQueueOnExit))
            }
            .launchIn(viewModelScope)
    }

    override fun handleEvent(event: PlaybackBehaviorSettingsUiEvent) {
        when (event) {
            is PlaybackBehaviorSettingsUiEvent.SetClearQueueOnExit ->
                handleSetClearQueueOnExit(event.enabled)
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

                is Resource.Error -> {
                    emitEffect(
                        PlaybackBehaviorSettingsUiEffect.ToggleError(
                            result.data?.toUserMessage()
                                ?: resolveString(R.string.settings_audiophile_toggle_error_fallback)
                        )
                    )
                }
            }
        }
    }
}
