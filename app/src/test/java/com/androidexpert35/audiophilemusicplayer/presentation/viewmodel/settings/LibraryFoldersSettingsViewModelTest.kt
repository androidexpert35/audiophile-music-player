package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings

import app.cash.turbine.test
import com.androidexpert35.audiophilemusicplayer.FakeNavigationManager
import com.androidexpert35.audiophilemusicplayer.MainDispatcherRule
import com.androidexpert35.audiophilemusicplayer.TestStringResolver
import com.androidexpert35.audiophilemusicplayer.TestUiErrorMapper
import com.androidexpert35.audiophilemusicplayer.domain.model.library.MusicFolder
import com.androidexpert35.audiophilemusicplayer.domain.usecase.AddMusicFolderUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveMusicFoldersUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.RemoveMusicFolderUseCase
import com.androidexpert35.audiophilemusicplayer.presentation.navigation.AppRoutes
import com.tony.coreui.domain.resource.Resource
import com.tony.coreui.domain.resource.ResourceError
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryFoldersSettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val observeMusicFoldersUseCase = mockk<ObserveMusicFoldersUseCase>()
    private val addMusicFolderUseCase = mockk<AddMusicFolderUseCase>()
    private val removeMusicFolderUseCase = mockk<RemoveMusicFolderUseCase>()
    private val navigationManager = FakeNavigationManager()

    private val musicFoldersFlow = MutableStateFlow(emptyList<MusicFolder>())

    @Test
    fun `given add folder tapped when handled then the folder chooser effect is emitted`() = runTest {
        stubObservationUseCases()
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.uiEffect.test {
            viewModel.onEvent(LibraryFoldersSettingsUiEvent.AddMusicFolderTapped)
            advanceUntilIdle()

            assertEquals(LibraryFoldersSettingsUiEffect.PickMusicFolder, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `given folder picked when grant succeeds then the folder joins the scan scope`() = runTest {
        stubObservationUseCases()
        coEvery { addMusicFolderUseCase.invoke(FOLDER_ID) } returns Resource.Success(Unit)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onEvent(LibraryFoldersSettingsUiEvent.MusicFolderPicked(FOLDER_ID))
        advanceUntilIdle()

        coVerify(exactly = 1) { addMusicFolderUseCase.invoke(FOLDER_ID) }
    }

    @Test
    fun `given folder picked when grant succeeds then the indexing screen opens`() = runTest {
        stubObservationUseCases()
        coEvery { addMusicFolderUseCase.invoke(FOLDER_ID) } returns Resource.Success(Unit)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onEvent(LibraryFoldersSettingsUiEvent.MusicFolderPicked(FOLDER_ID))
        advanceUntilIdle()

        assertEquals(AppRoutes.Onboarding.route, navigationManager.lastRoute)
    }

    @Test
    fun `given folder removal succeeds when handled then the indexing screen opens`() = runTest {
        stubObservationUseCases()
        coEvery { removeMusicFolderUseCase.invoke(FOLDER_ID) } returns Resource.Success(Unit)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onEvent(LibraryFoldersSettingsUiEvent.RemoveMusicFolder(FOLDER_ID))
        advanceUntilIdle()

        assertEquals(AppRoutes.Onboarding.route, navigationManager.lastRoute)
    }

    @Test
    fun `given folder chooser dismissed when result handled then no grant is attempted`() = runTest {
        stubObservationUseCases()
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onEvent(LibraryFoldersSettingsUiEvent.MusicFolderPicked(folderId = null))
        advanceUntilIdle()

        coVerify(exactly = 0) { addMusicFolderUseCase.invoke(any()) }
    }

    @Test
    fun `given folder removal fails when handled then a toggle error effect is emitted`() = runTest {
        stubObservationUseCases()
        coEvery { removeMusicFolderUseCase.invoke(FOLDER_ID) } returns Resource.Error(
            ResourceError.StorageError("could not release grant")
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.uiEffect.test {
            viewModel.onEvent(LibraryFoldersSettingsUiEvent.RemoveMusicFolder(FOLDER_ID))
            advanceUntilIdle()

            assertTrue(awaitItem() is LibraryFoldersSettingsUiEffect.ToggleError)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `given granted folders change when observed then the settings model lists them`() = runTest {
        stubObservationUseCases()
        val viewModel = createViewModel()
        advanceUntilIdle()

        musicFoldersFlow.value = listOf(
            MusicFolder(id = FOLDER_ID, displayPath = "Music/DSD", storageLabel = "Internal storage")
        )
        advanceUntilIdle()

        assertEquals(
            listOf("Music/DSD"),
            viewModel.uiState.value.data?.musicFolders?.map { it.displayPath }
        )
    }

    private fun stubObservationUseCases() {
        every { observeMusicFoldersUseCase.invoke() } returns musicFoldersFlow
    }

    private fun createViewModel(): LibraryFoldersSettingsViewModel = LibraryFoldersSettingsViewModel(
        observeMusicFoldersUseCase = observeMusicFoldersUseCase,
        addMusicFolderUseCase = addMusicFolderUseCase,
        removeMusicFolderUseCase = removeMusicFolderUseCase,
        navigationManager = navigationManager,
        stringResolver = TestStringResolver,
        uiErrorMapper = TestUiErrorMapper,
    )

    private companion object {
        const val FOLDER_ID = "content://com.android.externalstorage.documents/tree/primary%3AMusic"
    }
}
