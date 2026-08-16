package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings

import app.cash.turbine.test
import com.androidexpert35.audiophilemusicplayer.FakeNavigationManager
import com.androidexpert35.audiophilemusicplayer.MainDispatcherRule
import com.androidexpert35.audiophilemusicplayer.TestStringResolver
import com.androidexpert35.audiophilemusicplayer.TestUiErrorMapper
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveClearQueueOnExitUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.SetClearQueueOnExitUseCase
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackBehaviorSettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val observeClearQueueOnExitUseCase = mockk<ObserveClearQueueOnExitUseCase>()
    private val setClearQueueOnExitUseCase = mockk<SetClearQueueOnExitUseCase>()
    private val navigationManager = FakeNavigationManager()

    private val clearQueueOnExitFlow = MutableStateFlow(false)

    @Test
    fun `given clear queue on exit is enabled when selected then preference is saved and reflected`() = runTest {
        every { observeClearQueueOnExitUseCase.invoke() } returns clearQueueOnExitFlow
        coEvery { setClearQueueOnExitUseCase.invoke(true) } returns Resource.Success(Unit)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onEvent(PlaybackBehaviorSettingsUiEvent.SetClearQueueOnExit(true))
        advanceUntilIdle()

        coVerify(exactly = 1) { setClearQueueOnExitUseCase.invoke(true) }
        assertTrue(viewModel.uiState.value.data?.clearQueueOnExit == true)
    }

    @Test
    fun `given preference write fails when selected then a toggle error effect is emitted`() = runTest {
        every { observeClearQueueOnExitUseCase.invoke() } returns clearQueueOnExitFlow
        coEvery { setClearQueueOnExitUseCase.invoke(true) } returns Resource.Error(
            ResourceError.StorageError("could not persist")
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.uiEffect.test {
            viewModel.onEvent(PlaybackBehaviorSettingsUiEvent.SetClearQueueOnExit(true))
            advanceUntilIdle()

            assertTrue(awaitItem() is PlaybackBehaviorSettingsUiEffect.ToggleError)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun createViewModel(): PlaybackBehaviorSettingsViewModel = PlaybackBehaviorSettingsViewModel(
        observeClearQueueOnExitUseCase = observeClearQueueOnExitUseCase,
        setClearQueueOnExitUseCase = setClearQueueOnExitUseCase,
        navigationManager = navigationManager,
        stringResolver = TestStringResolver,
        uiErrorMapper = TestUiErrorMapper,
    )
}
