package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings

import androidx.lifecycle.viewModelScope
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.domain.model.common.toUserMessage
import com.androidexpert35.audiophilemusicplayer.domain.usecase.AddMusicFolderUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveMusicFoldersUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.RemoveMusicFolderUseCase
import com.androidexpert35.audiophilemusicplayer.presentation.navigation.AppRoutes
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
 * ViewModel powering the Library Folders settings sub-screen.
 *
 * @property observeMusicFoldersUseCase Source of truth for the folders the library is
 *   scanned from.
 * @property addMusicFolderUseCase Brings a folder chosen here into the library scan
 *   scope, so the user is not limited to the folder they picked during onboarding.
 * @property removeMusicFolderUseCase Drops a folder from the library scan scope.
 */
@HiltViewModel
class LibraryFoldersSettingsViewModel @Inject constructor(
    observeMusicFoldersUseCase: ObserveMusicFoldersUseCase,
    private val addMusicFolderUseCase: AddMusicFolderUseCase,
    private val removeMusicFolderUseCase: RemoveMusicFolderUseCase,
    navigationManager: NavigationManager,
    stringResolver: StringResolver,
    uiErrorMapper: UiErrorMapper
) : BaseViewModel<LibraryFoldersSettingsUiModel, LibraryFoldersSettingsUiEvent, LibraryFoldersSettingsUiEffect>(
    navigationManager = navigationManager,
    stringResolver = stringResolver,
    uiErrorMapper = uiErrorMapper
) {

    init {
        setSuccessState(LibraryFoldersSettingsUiModel())
        observeMusicFoldersUseCase()
            .onEach { folders ->
                val current = uiState.value.data ?: LibraryFoldersSettingsUiModel()
                setSuccessState(current.copy(musicFolders = folders))
            }
            .launchIn(viewModelScope)
    }

    override fun handleEvent(event: LibraryFoldersSettingsUiEvent) {
        when (event) {
            LibraryFoldersSettingsUiEvent.AddMusicFolderTapped ->
                emitEffect(LibraryFoldersSettingsUiEffect.PickMusicFolder)
            is LibraryFoldersSettingsUiEvent.MusicFolderPicked -> handleMusicFolderPicked(event.folderId)
            is LibraryFoldersSettingsUiEvent.RemoveMusicFolder -> handleRemoveMusicFolder(event.folderId)
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

    private fun emitError(result: Resource.Error) {
        emitEffect(
            LibraryFoldersSettingsUiEffect.ToggleError(
                result.data?.toUserMessage()
                    ?: resolveString(R.string.settings_audiophile_toggle_error_fallback)
            )
        )
    }
}
