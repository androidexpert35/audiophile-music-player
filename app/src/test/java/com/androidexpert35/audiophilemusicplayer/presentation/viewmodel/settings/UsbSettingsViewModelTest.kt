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
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveUsbAudioStatusUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.RefreshUsbAudioDevicesUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.RequestUsbAudioPermissionUseCase
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
class UsbSettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val observeUsbAudioStatusUseCase = mockk<ObserveUsbAudioStatusUseCase>()
    private val observeAudioTelemetryUseCase = mockk<ObserveAudioTelemetryUseCase>()
    private val refreshUsbAudioDevicesUseCase = mockk<RefreshUsbAudioDevicesUseCase>()
    private val requestUsbAudioPermissionUseCase = mockk<RequestUsbAudioPermissionUseCase>()
    private val navigationManager = FakeNavigationManager()

    private val usbAudioStatusFlow = MutableStateFlow(UsbAudioStatus())
    private val telemetryFlow = MutableStateFlow(AudioTelemetry())

    @Test
    fun `given usb refresh requested when it succeeds then refresh use case runs and progress resets`() = runTest {
        stubObservationUseCases()
        coEvery { refreshUsbAudioDevicesUseCase.invoke() } returns Resource.Success(Unit)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onEvent(UsbSettingsUiEvent.RefreshUsbAudioDevices)
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
            viewModel.onEvent(UsbSettingsUiEvent.RefreshUsbAudioDevices)
            advanceUntilIdle()

            val effect = awaitItem()
            assertTrue(effect is UsbSettingsUiEffect.ToggleError)
            cancelAndIgnoreRemainingEvents()
        }

        assertFalse(viewModel.uiState.value.data?.isUsbDeviceRefreshInProgress ?: true)
    }

    @Test
    fun `given permission request fails when handled then a toggle error effect is emitted`() = runTest {
        stubObservationUseCases()
        coEvery { requestUsbAudioPermissionUseCase.invoke() } returns Resource.Error(
            PlaybackResourceError("no dac connected")
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.uiEffect.test {
            viewModel.onEvent(UsbSettingsUiEvent.RequestUsbAudioPermission)
            advanceUntilIdle()

            assertTrue(awaitItem() is UsbSettingsUiEffect.ToggleError)
            cancelAndIgnoreRemainingEvents()
        }
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
        every { observeUsbAudioStatusUseCase.invoke() } returns usbAudioStatusFlow
        every { observeAudioTelemetryUseCase.invoke() } returns telemetryFlow
    }

    private fun createViewModel(): UsbSettingsViewModel = UsbSettingsViewModel(
        observeUsbAudioStatusUseCase = observeUsbAudioStatusUseCase,
        observeAudioTelemetryUseCase = observeAudioTelemetryUseCase,
        refreshUsbAudioDevicesUseCase = refreshUsbAudioDevicesUseCase,
        requestUsbAudioPermissionUseCase = requestUsbAudioPermissionUseCase,
        navigationManager = navigationManager,
        stringResolver = TestStringResolver,
        uiErrorMapper = TestUiErrorMapper,
    )
}
