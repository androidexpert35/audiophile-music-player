package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.onboarding

import com.androidexpert35.audiophilemusicplayer.MainDispatcherRule
import com.androidexpert35.audiophilemusicplayer.FakeNavigationManager
import com.androidexpert35.audiophilemusicplayer.TestStringResolver
import com.androidexpert35.audiophilemusicplayer.TestUiErrorMapper
import com.tony.coreui.domain.resource.Resource
import com.androidexpert35.audiophilemusicplayer.domain.model.indexing.MediaIndexingProgress
import com.androidexpert35.audiophilemusicplayer.domain.usecase.IsMediaLibraryIndexedUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ScanAndIndexMediaUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val scanAndIndexMediaUseCase = mockk<ScanAndIndexMediaUseCase>()
    private val isMediaLibraryIndexedUseCase = mockk<IsMediaLibraryIndexedUseCase>()
    private val navigationManager = FakeNavigationManager()

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
    fun `given permission granted and indexing needed when initialized then state completes and navigation effect is emitted`() = runTest {
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
        navigationManager = navigationManager,
        stringResolver = TestStringResolver,
        uiErrorMapper = TestUiErrorMapper
    )
}
