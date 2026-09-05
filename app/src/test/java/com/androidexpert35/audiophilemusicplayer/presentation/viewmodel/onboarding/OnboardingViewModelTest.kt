package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.onboarding

import com.androidexpert35.audiophilemusicplayer.FakeNavigationManager
import com.androidexpert35.audiophilemusicplayer.MainDispatcherRule
import com.androidexpert35.audiophilemusicplayer.TestStringResolver
import com.androidexpert35.audiophilemusicplayer.TestUiErrorMapper
import com.androidexpert35.audiophilemusicplayer.domain.model.common.LibraryResourceError
import com.androidexpert35.audiophilemusicplayer.domain.model.indexing.MediaIndexingProgress
import com.androidexpert35.audiophilemusicplayer.domain.usecase.AddMusicFolderUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.HasMusicFoldersUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.IsMediaLibraryIndexedUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ReportLibraryBugUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ScanAndIndexMediaUseCase
import com.tony.coreui.domain.resource.Resource
import com.tony.coreui.domain.resource.ResourceError
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val scanAndIndexMediaUseCase = mockk<ScanAndIndexMediaUseCase>()
    private val isMediaLibraryIndexedUseCase = mockk<IsMediaLibraryIndexedUseCase>()
    private val hasMusicFoldersUseCase = mockk<HasMusicFoldersUseCase>()
    private val addMusicFolderUseCase = mockk<AddMusicFolderUseCase>()
    private val reportLibraryBugUseCase = mockk<ReportLibraryBugUseCase>()
    private val navigationManager = FakeNavigationManager()

    @Test
    fun `reporting keeps the original dialog and prevents duplicate drafts`() = runTest {
        val failure = LibraryResourceError.SCAN_READ_FAILED
        every { scanAndIndexMediaUseCase.invoke() } returns flowOf(Resource.Error(failure))
        val completion = CompletableDeferred<Resource<Unit>>()
        coEvery { reportLibraryBugUseCase.invoke(failure) } coAnswers { completion.await() }
        val viewModel = createViewModel()
        viewModel.onEvent(OnboardingUiEvent.RetryIndexing)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.showErrorDialog)
        assertEquals(failure, viewModel.uiState.value.error?.type)
        viewModel.onEvent(OnboardingUiEvent.ReportBug)
        viewModel.onEvent(OnboardingUiEvent.ReportBug)
        runCurrent()
        assertTrue(viewModel.uiState.value.data?.preparingReport == true)
        completion.complete(Resource.Error(ResourceError.ServiceError("No email app installed", null)))
        advanceUntilIdle()
        coVerify(exactly = 1) { reportLibraryBugUseCase.invoke(failure) }
        assertEquals("No email app installed", viewModel.uiState.value.data?.reportFailure)
        assertEquals(false, viewModel.uiState.value.data?.preparingReport)
        assertEquals(failure, viewModel.uiState.value.error?.type)
        assertTrue(viewModel.uiState.value.showErrorDialog)
    }

    @Test
    fun `given missing permission when initialized then state requires permission`() = runTest {
        val viewModel = createViewModel()

        viewModel.onEvent(OnboardingUiEvent.Initialize(hasMediaPermission = false))
        advanceUntilIdle()

        assertEquals(
            OnboardingState.RequiresPermission,
            viewModel.uiState.value.data?.state
        )
    }

    @Test
    fun `given permission button tapped when handled then permission effect is emitted`() = runTest {
        val viewModel = createViewModel()
        val effectDeferred = async(start = CoroutineStart.UNDISPATCHED) {
            viewModel.uiEffect.first()
        }

        viewModel.onEvent(OnboardingUiEvent.RequestPermissionTapped)

        assertEquals(OnboardingUiEffect.RequestPermission, effectDeferred.await())
    }

    @Test
    fun `given permission granted and no music folder when initialized then state requires a folder`() = runTest {
        coEvery { hasMusicFoldersUseCase.invoke() } returns false

        val viewModel = createViewModel()

        viewModel.onEvent(OnboardingUiEvent.Initialize(hasMediaPermission = true))
        advanceUntilIdle()

        assertEquals(
            OnboardingState.RequiresMusicFolder(),
            viewModel.uiState.value.data?.state
        )
    }

    @Test
    fun `given no music folder when cached index exists then indexing is not skipped`() = runTest {
        // A library indexed before folders existed came from a whole-device scan and must
        // not be trusted: the user is sent back to the folder step instead.
        coEvery { hasMusicFoldersUseCase.invoke() } returns false
        coEvery { isMediaLibraryIndexedUseCase.invoke() } returns true

        val viewModel = createViewModel()

        viewModel.onEvent(OnboardingUiEvent.Initialize(hasMediaPermission = true))
        advanceUntilIdle()

        assertEquals(
            OnboardingState.RequiresMusicFolder(),
            viewModel.uiState.value.data?.state
        )
    }

    @Test
    fun `given folder chooser dismissed when result handled then folder step reports the failed attempt`() = runTest {
        val viewModel = createViewModel()

        viewModel.onEvent(OnboardingUiEvent.MusicFolderPicked(folderId = null))
        advanceUntilIdle()

        assertEquals(
            OnboardingState.RequiresMusicFolder(hasFailedAttempt = true),
            viewModel.uiState.value.data?.state
        )
    }

    @Test
    fun `given folder picked when grant succeeds then indexing runs and onboarding completes`() = runTest {
        coEvery { addMusicFolderUseCase.invoke(FOLDER_ID) } returns Resource.Success(Unit)
        every { scanAndIndexMediaUseCase.invoke() } returns flowOf(
            Resource.Success(
                MediaIndexingProgress(
                    progress = 1f,
                    currentFile = "Music/DSD/01 - So What.dsf",
                    indexedFiles = 1,
                    totalFiles = 1
                )
            )
        )

        val viewModel = createViewModel()
        val effectDeferred = async(start = CoroutineStart.UNDISPATCHED) {
            viewModel.uiEffect.first()
        }

        viewModel.onEvent(OnboardingUiEvent.MusicFolderPicked(folderId = FOLDER_ID))

        assertEquals(OnboardingUiEffect.NavigateToHome, effectDeferred.await())
        advanceUntilIdle()

        coVerify(exactly = 1) { addMusicFolderUseCase.invoke(FOLDER_ID) }
        assertEquals(OnboardingState.Completed, viewModel.uiState.value.data?.state)
    }

    @Test
    fun `given folder picked when grant fails then folder step stays actionable`() = runTest {
        coEvery { addMusicFolderUseCase.invoke(FOLDER_ID) } returns Resource.Error(
            ResourceError.StorageError("grant lost")
        )

        val viewModel = createViewModel()

        viewModel.onEvent(OnboardingUiEvent.MusicFolderPicked(folderId = FOLDER_ID))
        advanceUntilIdle()

        assertTrue(
            viewModel.uiState.value.data?.state is OnboardingState.RequiresMusicFolder
        )
        assertEquals(
            TestUiErrorMapper.mapResourceError(LibraryResourceError.FOLDER_FAILED).message,
            (viewModel.uiState.value.data?.state as OnboardingState.RequiresMusicFolder).errorMessage
        )
    }

    @Test
    fun `given scan failure when retry tapped then saved folders are scanned without another grant`() = runTest {
        every { scanAndIndexMediaUseCase.invoke() } returnsMany listOf(
            flowOf(Resource.Error(LibraryResourceError.STORAGE_UNAVAILABLE)),
            flowOf(Resource.Success(MediaIndexingProgress(1f, "song.flac", 1, 1)))
        )
        val viewModel = createViewModel()
        viewModel.onEvent(OnboardingUiEvent.RetryIndexing)
        advanceUntilIdle()

        assertEquals(
            OnboardingState.IndexingFailed(TestUiErrorMapper.mapResourceError(LibraryResourceError.STORAGE_UNAVAILABLE).message, true),
            viewModel.uiState.value.data?.state
        )
        viewModel.dismissErrorPopup()
        viewModel.onEvent(OnboardingUiEvent.MusicFolderPicked(null))
        advanceUntilIdle()
        assertEquals(
            OnboardingState.IndexingFailed(TestUiErrorMapper.mapResourceError(LibraryResourceError.STORAGE_UNAVAILABLE).message, true),
            viewModel.uiState.value.data?.state
        )

        val effect = async(start = CoroutineStart.UNDISPATCHED) { viewModel.uiEffect.first() }
        viewModel.onEvent(OnboardingUiEvent.RetryIndexing)
        assertEquals(OnboardingUiEffect.NavigateToHome, effect.await())
        advanceUntilIdle()
        assertEquals(OnboardingState.Completed, viewModel.uiState.value.data?.state)
        coVerify(exactly = 0) { addMusicFolderUseCase.invoke(any()) }
    }

    @Test
    fun `given scan throws when collecting then persistent failure replaces scanning`() = runTest {
        every { scanAndIndexMediaUseCase.invoke() } returns flow {
            throw IllegalStateException("Provider unavailable")
        }
        val viewModel = createViewModel()
        viewModel.onEvent(OnboardingUiEvent.RetryIndexing)
        advanceUntilIdle()
        assertEquals(
            OnboardingState.IndexingFailed(TestUiErrorMapper.mapResourceError(LibraryResourceError.SCAN_FAILED).message),
            viewModel.uiState.value.data?.state
        )
    }

    @Test
    fun `given permission granted and indexing needed when initialized then state completes and navigation effect is emitted`() = runTest {
        coEvery { hasMusicFoldersUseCase.invoke() } returns true
        every { scanAndIndexMediaUseCase.invoke() } returns flowOf(
            Resource.Success(
                MediaIndexingProgress(
                    progress = 0.45f,
                    currentFile = "Music/Example/Track01.flac",
                    indexedFiles = 1,
                    totalFiles = 2
                )
            ),
            Resource.Success(
                MediaIndexingProgress(
                    progress = 1f,
                    currentFile = "Music/Example/Track02.flac",
                    indexedFiles = 2,
                    totalFiles = 2
                )
            )
        )
        coEvery { isMediaLibraryIndexedUseCase.invoke() } returns false

        val viewModel = createViewModel()
        val effectDeferred = async(start = CoroutineStart.UNDISPATCHED) {
            viewModel.uiEffect.first()
        }

        viewModel.onEvent(OnboardingUiEvent.Initialize(hasMediaPermission = true))

        assertEquals(OnboardingUiEffect.NavigateToHome, effectDeferred.await())
        advanceUntilIdle()

        assertEquals(
            OnboardingState.Completed,
            viewModel.uiState.value.data?.state
        )
    }

    @Test
    fun `given permission granted and cached library when initialized then navigation skips onboarding`() = runTest {
        coEvery { hasMusicFoldersUseCase.invoke() } returns true
        coEvery { isMediaLibraryIndexedUseCase.invoke() } returns true

        val viewModel = createViewModel()
        val effectDeferred = async(start = CoroutineStart.UNDISPATCHED) {
            viewModel.uiEffect.first()
        }

        viewModel.onEvent(OnboardingUiEvent.Initialize(hasMediaPermission = true))

        assertEquals(OnboardingUiEffect.NavigateToHome, effectDeferred.await())
        assertEquals(OnboardingState.Completed, viewModel.uiState.value.data?.state)
    }

    private fun createViewModel(): OnboardingViewModel = OnboardingViewModel(
        scanAndIndexMediaUseCase = scanAndIndexMediaUseCase,
        isMediaLibraryIndexedUseCase = isMediaLibraryIndexedUseCase,
        hasMusicFoldersUseCase = hasMusicFoldersUseCase,
        addMusicFolderUseCase = addMusicFolderUseCase,
        reportLibraryBugUseCase = reportLibraryBugUseCase,
        navigationManager = navigationManager,
        stringResolver = TestStringResolver,
        uiErrorMapper = TestUiErrorMapper
    )

    private companion object {
        const val FOLDER_ID = "content://com.android.externalstorage.documents/tree/primary%3AMusic"
    }
}
