package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings

import app.cash.turbine.test
import com.androidexpert35.audiophilemusicplayer.FakeNavigationManager
import com.androidexpert35.audiophilemusicplayer.MainDispatcherRule
import com.androidexpert35.audiophilemusicplayer.TestStringResolver
import com.androidexpert35.audiophilemusicplayer.TestUiErrorMapper
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioCodec
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioTelemetry
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.BitPerfectDiagnostics
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.OutputRouteKind
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.OutputStreamInfo
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.SueStatus
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.UsbAudioStatus
import com.androidexpert35.audiophilemusicplayer.domain.model.common.PlaybackResourceError
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveAudioTelemetryUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveAudiophileEngineEnabledUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveHiResRemasterEnabledUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveSueEnabledUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveUsbAudioStatusUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.RefreshUsbAudioDevicesUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.RequestUsbAudioPermissionUseCase
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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val observeAudiophileEngineEnabledUseCase = mockk<ObserveAudiophileEngineEnabledUseCase>()
    private val observeUsbAudioStatusUseCase = mockk<ObserveUsbAudioStatusUseCase>()
    private val observeSueEnabledUseCase = mockk<ObserveSueEnabledUseCase>()
    private val observeHiResRemasterEnabledUseCase = mockk<ObserveHiResRemasterEnabledUseCase>()
    private val observeAudioTelemetryUseCase = mockk<ObserveAudioTelemetryUseCase>()
    private val setAudiophileEngineEnabledUseCase = mockk<SetAudiophileEngineEnabledUseCase>()
    private val setSueEnabledUseCase = mockk<SetSueEnabledUseCase>()
    private val setHiResRemasterEnabledUseCase = mockk<SetHiResRemasterEnabledUseCase>()
    private val refreshUsbAudioDevicesUseCase = mockk<RefreshUsbAudioDevicesUseCase>()
    private val requestUsbAudioPermissionUseCase = mockk<RequestUsbAudioPermissionUseCase>()
    private val navigationManager = FakeNavigationManager()

    private val audiophileEnabledFlow = MutableStateFlow(false)
    private val usbAudioStatusFlow = MutableStateFlow(UsbAudioStatus())
    private val sueEnabledFlow = MutableStateFlow(true)
    private val hiResEnabledFlow = MutableStateFlow(true)
    private val telemetryFlow = MutableStateFlow(AudioTelemetry())

    @Test
    fun `given usb refresh requested when it succeeds then refresh use case runs and progress resets`() = runTest {
        stubObservationUseCases()
        coEvery { refreshUsbAudioDevicesUseCase.invoke() } returns Resource.Success(Unit)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onEvent(SettingsUiEvent.RefreshUsbAudioDevices)
        advanceUntilIdle()

        coVerify(exactly = 1) { refreshUsbAudioDevicesUseCase.invoke() }
        assertFalse(viewModel.uiState.value.data?.isUsbDeviceRefreshInProgress ?: true)
    }

    @Test
    fun `given usb refresh requested when it fails then toggle error effect is emitted and progress resets`() = runTest {
        stubObservationUseCases()
        coEvery { refreshUsbAudioDevicesUseCase.invoke() } returns Resource.Error(
            PlaybackResourceError("refresh failed")
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.uiEffect.test {
            viewModel.onEvent(SettingsUiEvent.RefreshUsbAudioDevices)
            advanceUntilIdle()

            val effect = awaitItem()
            assert(effect is SettingsUiEffect.ToggleError)
            cancelAndIgnoreRemainingEvents()
        }

        assertFalse(viewModel.uiState.value.data?.isUsbDeviceRefreshInProgress ?: true)
    }

    @Test
    fun `given sue toggle event when persistence succeeds then ui state reflects new sue value`() = runTest {
        stubObservationUseCases()
        coEvery { setSueEnabledUseCase.invoke(false) } returns Resource.Success(Unit)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onEvent(SettingsUiEvent.SetSueEnabled(false))
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

        viewModel.onEvent(SettingsUiEvent.SetHiResRemasterEnabled(false))
        advanceUntilIdle()

        coVerify(exactly = 1) { setHiResRemasterEnabledUseCase.invoke(false) }
        assertFalse(viewModel.uiState.value.data?.hiResRemasterEnabled ?: true)
        assertTrue(viewModel.uiState.value.data?.sueEnabled == true)
    }

    @Test
    fun `given scanner misses dac when telemetry confirms usb playback then settings shows active device`() = runTest {
        stubObservationUseCases()
        val viewModel = createViewModel()
        advanceUntilIdle()

        telemetryFlow.value = AudioTelemetry(
            streamInfo = OutputStreamInfo.Pcm(
                codec = AudioCodec.FLAC,
                sampleRateHz = 96_000,
                bitDepth = 24,
                bitrateKbps = 0,
            ),
            bitPerfectDiagnostics = BitPerfectDiagnostics(
                activeDeviceName = "External USB DAC",
                outputRouteKind = OutputRouteKind.USB,
            ),
            isAudiophileEngineActive = true,
        )
        advanceUntilIdle()

        val model = viewModel.uiState.value.data
        assertFalse(model?.usbAudioStatus?.isDeviceConnected ?: true)
        assertTrue(model?.isUsbPlaybackActive == true)
        assertEquals("External USB DAC", model?.activeUsbPlaybackDeviceName)
    }

    @Test
    fun `given lossy restoration is active over usb then settings keeps dac active`() = runTest {
        assertProcessedUsbPlaybackIsActive(
            codec = AudioCodec.MP3,
            sueStatus = SueStatus(
                isEnabled = true,
                isActive = true,
                isLossy = true,
                isProvisioned = true,
            )
        )
    }

    @Test
    fun `given hi res remaster is active over usb then settings keeps dac active`() = runTest {
        assertProcessedUsbPlaybackIsActive(
            codec = AudioCodec.FLAC,
            sueStatus = SueStatus(
                isHiResRemasterEnabled = true,
                isHiResRemasterActive = true,
                isProvisioned = true,
            )
        )
    }

    @Test
    fun `given usb playback stops when telemetry becomes idle then runtime dac fallback is cleared`() = runTest {
        stubObservationUseCases()
        val viewModel = createViewModel()
        advanceUntilIdle()

        telemetryFlow.value = AudioTelemetry(
            streamInfo = OutputStreamInfo.Pcm(
                codec = AudioCodec.FLAC,
                sampleRateHz = 96_000,
                bitDepth = 24,
                bitrateKbps = 0,
            ),
            bitPerfectDiagnostics = BitPerfectDiagnostics(
                activeDeviceName = "External USB DAC",
                outputRouteKind = OutputRouteKind.USB,
            ),
            isAudiophileEngineActive = true,
        )
        advanceUntilIdle()
        telemetryFlow.value = AudioTelemetry.IDLE
        advanceUntilIdle()

        val model = viewModel.uiState.value.data
        assertFalse(model?.isUsbPlaybackActive ?: true)
        assertNull(model?.activeUsbPlaybackDeviceName)
    }

    private suspend fun TestScope.assertProcessedUsbPlaybackIsActive(
        codec: AudioCodec,
        sueStatus: SueStatus,
    ) {
        stubObservationUseCases()
        val viewModel = createViewModel()
        advanceUntilIdle()

        telemetryFlow.value = AudioTelemetry(
            streamInfo = OutputStreamInfo.Pcm(
                codec = codec,
                sampleRateHz = 96_000,
                bitDepth = 32,
                bitrateKbps = 0,
            ),
            bitPerfectDiagnostics = BitPerfectDiagnostics(
                activeDeviceName = "External USB DAC",
                outputRouteKind = OutputRouteKind.USB,
            ),
            isAudiophileEngineActive = true,
            sueStatus = sueStatus,
        )
        advanceUntilIdle()

        val model = viewModel.uiState.value.data
        assertTrue(model?.isUsbPlaybackActive == true)
        assertEquals("External USB DAC", model?.activeUsbPlaybackDeviceName)
    }

    private fun stubObservationUseCases() {
        every { observeAudiophileEngineEnabledUseCase.invoke() } returns audiophileEnabledFlow
        every { observeUsbAudioStatusUseCase.invoke() } returns usbAudioStatusFlow
        every { observeSueEnabledUseCase.invoke() } returns sueEnabledFlow
        every { observeHiResRemasterEnabledUseCase.invoke() } returns hiResEnabledFlow
        every { observeAudioTelemetryUseCase.invoke() } returns telemetryFlow
    }

    private fun createViewModel(): SettingsViewModel = SettingsViewModel(
        observeAudiophileEngineEnabledUseCase = observeAudiophileEngineEnabledUseCase,
        observeUsbAudioStatusUseCase = observeUsbAudioStatusUseCase,
        observeSueEnabledUseCase = observeSueEnabledUseCase,
        observeHiResRemasterEnabledUseCase = observeHiResRemasterEnabledUseCase,
        observeAudioTelemetryUseCase = observeAudioTelemetryUseCase,
        setAudiophileEngineEnabledUseCase = setAudiophileEngineEnabledUseCase,
        setSueEnabledUseCase = setSueEnabledUseCase,
        setHiResRemasterEnabledUseCase = setHiResRemasterEnabledUseCase,
        refreshUsbAudioDevicesUseCase = refreshUsbAudioDevicesUseCase,
        requestUsbAudioPermissionUseCase = requestUsbAudioPermissionUseCase,
        navigationManager = navigationManager,
        stringResolver = TestStringResolver,
        uiErrorMapper = TestUiErrorMapper,
    )
}
