package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings

import androidx.lifecycle.viewModelScope
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveAudiophileEngineEnabledUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveClearQueueOnExitUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveLibraryDisplayPreferencesUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveMusicFoldersUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveUsbAudioStatusUseCase
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library.LibraryContentType
import com.tony.coreui.data.strings.StringResolver
import com.tony.coreui.presentation.error.UiErrorMapper
import com.tony.coreui.presentation.navigation.NavigationManager
import com.tony.coreui.presentation.viewmodel.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject

/**
 * ViewModel powering the Settings hub screen.
 *
 * Combines the small pieces of live state needed to render each category card's
 * status subtitle. All the actual settings controls live in the per-category
 * sub-screens, each with its own ViewModel — this hub is deliberately lightweight.
 *
 * @property observeAudiophileEngineEnabledUseCase Source of truth for the Audio Engine card.
 * @property observeUsbAudioStatusUseCase Source of truth for the USB card.
 * @property observeMusicFoldersUseCase Source of truth for the Library Folders card.
 * @property observeLibraryDisplayPreferencesUseCase Source of truth for the Library
 *   Sections card's visible-section count.
 * @property observeClearQueueOnExitUseCase Source of truth for the Playback Behavior card.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    observeAudiophileEngineEnabledUseCase: ObserveAudiophileEngineEnabledUseCase,
    observeUsbAudioStatusUseCase: ObserveUsbAudioStatusUseCase,
    observeMusicFoldersUseCase: ObserveMusicFoldersUseCase,
    observeLibraryDisplayPreferencesUseCase: ObserveLibraryDisplayPreferencesUseCase,
    observeClearQueueOnExitUseCase: ObserveClearQueueOnExitUseCase,
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
            observeMusicFoldersUseCase(),
            observeLibraryDisplayPreferencesUseCase(),
            observeClearQueueOnExitUseCase(),
        ) { engineEnabled, usbStatus, musicFolders, libraryDisplayPreferences, clearQueueOnExit ->
            SettingsUiModel(
                audiophileEngineEnabled = engineEnabled,
                isUsbDacConnected = usbStatus.isDeviceConnected,
                musicFolderCount = musicFolders.size,
                visibleLibrarySectionCount = LibraryContentType.entries.count { contentType ->
                    libraryDisplayPreferences.preferenceFor(contentType.name).isVisible
                },
                totalLibrarySectionCount = LibraryContentType.entries.size,
                clearQueueOnExit = clearQueueOnExit,
            )
        }
            .onEach { model -> setSuccessState(model) }
            .launchIn(viewModelScope)
    }

    override fun handleEvent(event: SettingsUiEvent) {
        when (event) {
            is SettingsUiEvent.OpenCategory -> navigateToRoute(event.category.route)
        }
    }
}
