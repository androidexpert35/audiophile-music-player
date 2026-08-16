package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings

import com.androidexpert35.audiophilemusicplayer.FakeNavigationManager
import com.androidexpert35.audiophilemusicplayer.MainDispatcherRule
import com.androidexpert35.audiophilemusicplayer.TestStringResolver
import com.androidexpert35.audiophilemusicplayer.TestUiErrorMapper
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.UsbAudioStatus
import com.androidexpert35.audiophilemusicplayer.domain.model.library.LibraryDisplayPreferences
import com.androidexpert35.audiophilemusicplayer.domain.model.library.LibrarySectionDisplayPreference
import com.androidexpert35.audiophilemusicplayer.domain.model.library.MusicFolder
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveAudiophileEngineEnabledUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveClearQueueOnExitUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveLibraryDisplayPreferencesUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveMusicFoldersUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveUsbAudioStatusUseCase
import com.androidexpert35.audiophilemusicplayer.presentation.screen.settings.components.SettingsCategory
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val observeAudiophileEngineEnabledUseCase = mockk<ObserveAudiophileEngineEnabledUseCase>()
    private val observeUsbAudioStatusUseCase = mockk<ObserveUsbAudioStatusUseCase>()
    private val observeMusicFoldersUseCase = mockk<ObserveMusicFoldersUseCase>()
    private val observeLibraryDisplayPreferencesUseCase = mockk<ObserveLibraryDisplayPreferencesUseCase>()
    private val observeClearQueueOnExitUseCase = mockk<ObserveClearQueueOnExitUseCase>()
    private val navigationManager = FakeNavigationManager()

    private val audiophileEnabledFlow = MutableStateFlow(false)
    private val usbAudioStatusFlow = MutableStateFlow(UsbAudioStatus())
    private val musicFoldersFlow = MutableStateFlow(emptyList<MusicFolder>())
    private val libraryDisplayPreferencesFlow = MutableStateFlow(LibraryDisplayPreferences())
    private val clearQueueOnExitFlow = MutableStateFlow(false)

    @Test
    fun `given every source flow when combined then hub model reflects all category subtitles`() = runTest {
        stubObservationUseCases()
        audiophileEnabledFlow.value = true
        usbAudioStatusFlow.value = UsbAudioStatus(isDeviceConnected = true)
        musicFoldersFlow.value = listOf(
            MusicFolder(id = "1", displayPath = "Music", storageLabel = "Internal storage")
        )
        clearQueueOnExitFlow.value = true

        val viewModel = createViewModel()
        advanceUntilIdle()

        val model = viewModel.uiState.value.data
        assertEquals(true, model?.audiophileEngineEnabled)
        assertEquals(true, model?.isUsbDacConnected)
        assertEquals(1, model?.musicFolderCount)
        assertEquals(4, model?.totalLibrarySectionCount)
        assertEquals(4, model?.visibleLibrarySectionCount)
        assertEquals(true, model?.clearQueueOnExit)
    }

    @Test
    fun `given a hidden section when preferences are observed then visible count excludes it`() = runTest {
        stubObservationUseCases()
        libraryDisplayPreferencesFlow.value = LibraryDisplayPreferences(
            sections = mapOf("PLAYLISTS" to LibrarySectionDisplayPreference(isVisible = false))
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        val model = viewModel.uiState.value.data
        assertEquals(4, model?.totalLibrarySectionCount)
        assertEquals(3, model?.visibleLibrarySectionCount)
    }

    @Test
    fun `given open category event when handled then the category route is requested`() = runTest {
        stubObservationUseCases()
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onEvent(SettingsUiEvent.OpenCategory(SettingsCategory.USB))
        advanceUntilIdle()

        assertEquals(SettingsCategory.USB.route, navigationManager.lastRoute)
    }

    private fun stubObservationUseCases() {
        every { observeAudiophileEngineEnabledUseCase.invoke() } returns audiophileEnabledFlow
        every { observeUsbAudioStatusUseCase.invoke() } returns usbAudioStatusFlow
        every { observeMusicFoldersUseCase.invoke() } returns musicFoldersFlow
        every { observeLibraryDisplayPreferencesUseCase.invoke() } returns libraryDisplayPreferencesFlow
        every { observeClearQueueOnExitUseCase.invoke() } returns clearQueueOnExitFlow
    }

    private fun createViewModel(): SettingsViewModel = SettingsViewModel(
        observeAudiophileEngineEnabledUseCase = observeAudiophileEngineEnabledUseCase,
        observeUsbAudioStatusUseCase = observeUsbAudioStatusUseCase,
        observeMusicFoldersUseCase = observeMusicFoldersUseCase,
        observeLibraryDisplayPreferencesUseCase = observeLibraryDisplayPreferencesUseCase,
        observeClearQueueOnExitUseCase = observeClearQueueOnExitUseCase,
        navigationManager = navigationManager,
        stringResolver = TestStringResolver,
        uiErrorMapper = TestUiErrorMapper,
    )
}
