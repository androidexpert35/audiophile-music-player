package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings

import app.cash.turbine.test
import com.androidexpert35.audiophilemusicplayer.FakeNavigationManager
import com.androidexpert35.audiophilemusicplayer.MainDispatcherRule
import com.androidexpert35.audiophilemusicplayer.TestStringResolver
import com.androidexpert35.audiophilemusicplayer.TestUiErrorMapper
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioTelemetry
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.SueStatus
import com.androidexpert35.audiophilemusicplayer.domain.model.common.PlaybackResourceError
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveAudioTelemetryUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveAudiophileEngineEnabledUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveHiResRemasterEnabledUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveSueEnabledUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.SetAudiophileEngineEnabledUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.SetHiResRemasterEnabledUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.SetSueEnabledUseCase
import com.tony.coreui.domain.resource.Resource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AudioEngineSettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val observeAudiophileEngineEnabledUseCase = mockk<ObserveAudiophileEngineEnabledUseCase>()
    private val observeSueEnabledUseCase = mockk<ObserveSueEnabledUseCase>()
    private val observeHiResRemasterEnabledUseCase = mockk<ObserveHiResRemasterEnabledUseCase>()
    private val observeAudioTelemetryUseCase = mockk<ObserveAudioTelemetryUseCase>()
    private val setAudiophileEngineEnabledUseCase = mockk<SetAudiophileEngineEnabledUseCase>()
    private val setSueEnabledUseCase = mockk<SetSueEnabledUseCase>()
    private val setHiResRemasterEnabledUseCase = mockk<SetHiResRemasterEnabledUseCase>()
    private val navigationManager = FakeNavigationManager()

    private val audiophileEnabledFlow = MutableStateFlow(false)
    private val sueEnabledFlow = MutableStateFlow(true)
    private val hiResEnabledFlow = MutableStateFlow(true)
    private val telemetryFlow = MutableStateFlow(AudioTelemetry())

    @Test
    fun `given sue toggle event when persistence succeeds then ui state reflects new sue value`() = runTest {
        stubObservationUseCases()
        coEvery { setSueEnabledUseCase.invoke(false) } returns Resource.Success(Unit)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onEvent(AudioEngineSettingsUiEvent.SetSueEnabled(false))
        advanceUntilIdle()

        coVerify(exactly = 1) { setSueEnabledUseCase.invoke(false) }
        assertFalse(viewModel.uiState.value.data?.sueEnabled ?: true)
    }

    @Test
    fun `given hi res remaster toggle event when persistence succeeds then ui state updates without mutating sue state`() = runTest {
        stubObservationUseCases()
        coEvery { setHiResRemasterEnabledUseCase.invoke(false) } returns Resource.Success(Unit)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onEvent(AudioEngineSettingsUiEvent.SetHiResRemasterEnabled(false))
        advanceUntilIdle()

        coVerify(exactly = 1) { setHiResRemasterEnabledUseCase.invoke(false) }
        assertFalse(viewModel.uiState.value.data?.hiResRemasterEnabled ?: true)
        assertTrue(viewModel.uiState.value.data?.sueEnabled == true)
    }

    @Test
    fun `given engine toggle fails when persistence errors then a toggle error effect is emitted`() = runTest {
        stubObservationUseCases()
        coEvery { setAudiophileEngineEnabledUseCase.invoke(true) } returns Resource.Error(
            PlaybackResourceError("engine switch failed")
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.uiEffect.test {
            viewModel.onEvent(AudioEngineSettingsUiEvent.SetAudiophileEngineEnabled(true))
            advanceUntilIdle()

            assertTrue(awaitItem() is AudioEngineSettingsUiEffect.ToggleError)
            cancelAndIgnoreRemainingEvents()
        }
        assertFalse(viewModel.uiState.value.data?.isAudiophileEngineSwitchInProgress ?: true)
    }

    @Test
    fun `given telemetry emits sue status when observed then ui model surfaces it`() = runTest {
        stubObservationUseCases()
        val viewModel = createViewModel()
        advanceUntilIdle()

        val sueStatus = SueStatus(isEnabled = true, isActive = true, isLossy = true, isProvisioned = true)
        telemetryFlow.value = AudioTelemetry(sueStatus = sueStatus)
        advanceUntilIdle()

        assertEqualsSueStatus(sueStatus, viewModel.uiState.value.data?.sueStatus)
    }

    private fun assertEqualsSueStatus(expected: SueStatus, actual: SueStatus?) {
        org.junit.Assert.assertEquals(expected, actual)
    }

    private fun stubObservationUseCases() {
        every { observeAudiophileEngineEnabledUseCase.invoke() } returns audiophileEnabledFlow
        every { observeSueEnabledUseCase.invoke() } returns sueEnabledFlow
        every { observeHiResRemasterEnabledUseCase.invoke() } returns hiResEnabledFlow
        every { observeAudioTelemetryUseCase.invoke() } returns telemetryFlow
    }

    private fun createViewModel(): AudioEngineSettingsViewModel = AudioEngineSettingsViewModel(
        observeAudiophileEngineEnabledUseCase = observeAudiophileEngineEnabledUseCase,
        observeSueEnabledUseCase = observeSueEnabledUseCase,
        observeHiResRemasterEnabledUseCase = observeHiResRemasterEnabledUseCase,
        observeAudioTelemetryUseCase = observeAudioTelemetryUseCase,
        setAudiophileEngineEnabledUseCase = setAudiophileEngineEnabledUseCase,
        setSueEnabledUseCase = setSueEnabledUseCase,
        setHiResRemasterEnabledUseCase = setHiResRemasterEnabledUseCase,
        navigationManager = navigationManager,
        stringResolver = TestStringResolver,
        uiErrorMapper = TestUiErrorMapper,
    )
}
