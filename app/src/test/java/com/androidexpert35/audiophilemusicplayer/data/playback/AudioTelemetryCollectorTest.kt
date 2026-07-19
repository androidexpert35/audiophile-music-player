package com.androidexpert35.audiophilemusicplayer.data.playback

import com.androidexpert35.audiophilemusicplayer.data.playback.engine.AudioEngineManager
import com.androidexpert35.audiophilemusicplayer.data.playback.engine.EnginePlaybackState
import com.androidexpert35.audiophilemusicplayer.data.playback.engine.EngineType
import com.androidexpert35.audiophilemusicplayer.data.playback.native_.AudioFormatInfo
import com.androidexpert35.audiophilemusicplayer.data.playback.native_.PipelinePathReport
import com.androidexpert35.audiophilemusicplayer.data.playback.native_.SueInfo
import com.androidexpert35.audiophilemusicplayer.data.playback.usb.UsbVolumeController
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioCodec
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.OutputStreamInfo
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.OutputRouteKind
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.TelemetryStatus
import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [AudioTelemetryCollector].
 *
 * Confirms that a populated standard-style format and path report still produce
 * a non-idle telemetry snapshot once playback becomes active.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AudioTelemetryCollectorTest {

    // The collector logs on every snapshot; android.util.Log is unavailable on
    // the local JVM and an unstubbed call would kill the combine coroutine,
    // freezing telemetry at IDLE.
    @Before
    fun setUpAndroidLog() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
    }

    @After
    fun tearDownAndroidLog() {
        unmockkStatic(Log::class)
    }

    // Helper that creates an AudioPathValidator mock whose pathState is backed
    // by the supplied MutableStateFlow. Defaults to the idle/false state.
    private fun mockValidator(
        pathState: MutableStateFlow<AudioPathState> = MutableStateFlow(AudioPathState()),
    ): AudioPathValidator = mockk<AudioPathValidator>().also {
        every { it.pathState } returns pathState
    }

    private fun mockVolumeController(
        volumePct: MutableStateFlow<Int> = MutableStateFlow(100),
    ): UsbVolumeController = mockk<UsbVolumeController>().also {
        every { it.volumePct } returns volumePct
    }

    @Test
    fun `given standard style format and path report when playback starts then collector emits telemetry`() = runTest {
        val currentFormat = MutableStateFlow<AudioFormatInfo?>(null)
        val pathReport = MutableStateFlow<PipelinePathReport?>(null)
        val state = MutableStateFlow(EnginePlaybackState.IDLE)
        val activeEngineType = MutableStateFlow(EngineType.STANDARD)
        val engine = mockk<AudioEngineManager>()

        every { engine.currentFormat } returns currentFormat
        every { engine.pathReport } returns pathReport
        every { engine.state } returns state
        every { engine.activeEngineType } returns activeEngineType
        every { engine.currentUri } returns MutableStateFlow(null)

        val collector = AudioTelemetryCollector(
            engine = engine,
            audioPathValidator = mockValidator(
                MutableStateFlow(AudioPathState(pathStatus = AudioPathStatus.RESAMPLED))
            ),
            usbVolumeController = mockVolumeController(),
            appScope = CoroutineScope(
                backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)
            ),
        )

        currentFormat.value = AudioFormatInfo(
            sampleRateHz = 48_000,
            channelCount = 2,
            sourceBitDepth = 24,
            androidPcmEncoding = android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED,
            bytesPerSample = 3,
            durationMs = 0L,
            bitrateKbps = 320,
            codec = AudioCodec.AAC
        )
        pathReport.value = PipelinePathReport(
            usedDirectFlag = false,
            usedFloatFallback = false,
            encoding = android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED,
            sampleRateHz = 48_000,
            channelMask = 0,
            bufferFrames = 0,
            nativeOutputSampleRateHz = 0,
            framesPerBuffer = 0,
            routedDeviceType = 0,
            routedDeviceName = null,
            audioSessionId = 37
        )
        state.value = EnginePlaybackState.PLAYING

        runCurrent()

        val snapshot = collector.telemetry.value
        val pcmInfo = snapshot.streamInfo as OutputStreamInfo.Pcm
        assertEquals(48_000, pcmInfo.sampleRateHz)
        assertEquals(24, pcmInfo.bitDepth)
        assertEquals(AudioCodec.AAC, pcmInfo.codec)
        assertEquals(320, pcmInfo.bitrateKbps)
        assertEquals(TelemetryStatus.INACTIVE, snapshot.isDirectPlayback)
        // RESAMPLED means the validator confirmed a resolved (non-idle) path that
        // is not bit-perfect — maps to INACTIVE, distinct from the UNAVAILABLE
        // ("cannot determine") status reserved for an unresolved UNKNOWN pathStatus.
        assertEquals(TelemetryStatus.INACTIVE, snapshot.isBitPerfect)
        assertTrue(snapshot != com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioTelemetry.IDLE)
    }

    @Test
    fun `given audiophile direct path and mixer bit perfect when sample rate matches native then collector reports bit perfect`() = runTest {
        val currentFormat = MutableStateFlow<AudioFormatInfo?>(null)
        val pathReport = MutableStateFlow<PipelinePathReport?>(null)
        val state = MutableStateFlow(EnginePlaybackState.IDLE)
        val activeEngineType = MutableStateFlow(EngineType.AUDIOPHILE)
        val engine = mockk<AudioEngineManager>()

        every { engine.currentFormat } returns currentFormat
        every { engine.pathReport } returns pathReport
        every { engine.state } returns state
        every { engine.activeEngineType } returns activeEngineType
        every { engine.currentUri } returns MutableStateFlow(null)

        // AudioPathValidator confirms DIRECT_BIT_PERFECT — the single source of
        // truth for isBitPerfect in the new pipeline.
        val collector = AudioTelemetryCollector(
            engine = engine,
            audioPathValidator = mockValidator(
                pathState = MutableStateFlow(
                    AudioPathState(
                        pathStatus = AudioPathStatus.DIRECT_BIT_PERFECT,
                        isBitPerfectConfirmed = true,
                    )
                )
            ),
            usbVolumeController = mockVolumeController(),
            appScope = CoroutineScope(
                backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)
            ),
        )

        currentFormat.value = AudioFormatInfo(
            sampleRateHz = 192_000,
            channelCount = 2,
            sourceBitDepth = 24,
            androidPcmEncoding = android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED,
            bytesPerSample = 3,
            durationMs = 0L,
            bitrateKbps = 0,
            codec = AudioCodec.FLAC,
        )
        pathReport.value = PipelinePathReport(
            usedDirectFlag = true,
            usedFloatFallback = false,
            encoding = android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED,
            sampleRateHz = 192_000,
            channelMask = 0,
            bufferFrames = 0,
            nativeOutputSampleRateHz = 192_000,
            framesPerBuffer = 0,
            routedDeviceType = 0,
            routedDeviceName = null,
            audioSessionId = 0,
        )
        state.value = EnginePlaybackState.PLAYING

        runCurrent()

        val snapshot = collector.telemetry.value
        assertEquals(TelemetryStatus.ACTIVE, snapshot.isDirectPlayback)
        assertEquals(TelemetryStatus.ACTIVE, snapshot.isBitPerfect)
    }

    @Test
    fun `given direct USB PCM below unity when volume changes then bit perfect becomes inactive`() = runTest {
        val currentFormat = MutableStateFlow<AudioFormatInfo?>(
            AudioFormatInfo(
                sampleRateHz = 44_100,
                channelCount = 2,
                sourceBitDepth = 16,
                androidPcmEncoding = android.media.AudioFormat.ENCODING_PCM_16BIT,
                bytesPerSample = 2,
                durationMs = 0L,
                bitrateKbps = 0,
                codec = AudioCodec.FLAC,
            )
        )
        val pathReport = MutableStateFlow<PipelinePathReport?>(
            PipelinePathReport(
                usedDirectFlag = true,
                usedFloatFallback = false,
                encoding = android.media.AudioFormat.ENCODING_PCM_32BIT,
                sampleRateHz = 44_100,
                channelMask = 0,
                bufferFrames = 0,
                nativeOutputSampleRateHz = 44_100,
                framesPerBuffer = 8,
                routedDeviceType = android.hardware.usb.UsbConstants.USB_CLASS_AUDIO,
                routedDeviceName = "USB DAC",
                audioSessionId = 0,
                usbPcmSubslotBitDepth = 32,
                usbPcmValidBitDepth = 32,
            )
        )
        val state = MutableStateFlow(EnginePlaybackState.PLAYING)
        val activeEngineType = MutableStateFlow(EngineType.AUDIOPHILE)
        val volumePct = MutableStateFlow(100)
        val engine = mockk<AudioEngineManager>()
        every { engine.currentFormat } returns currentFormat
        every { engine.pathReport } returns pathReport
        every { engine.state } returns state
        every { engine.activeEngineType } returns activeEngineType
        every { engine.currentUri } returns MutableStateFlow(null)

        val collector = AudioTelemetryCollector(
            engine = engine,
            audioPathValidator = mockValidator(
                MutableStateFlow(
                    AudioPathState(pathStatus = AudioPathStatus.DIRECT_BIT_PERFECT)
                )
            ),
            usbVolumeController = mockVolumeController(volumePct),
            appScope = CoroutineScope(
                backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)
            ),
        )

        runCurrent()
        assertEquals(TelemetryStatus.ACTIVE, collector.telemetry.value.isBitPerfect)
        assertEquals(
            16,
            (collector.telemetry.value.streamInfo as OutputStreamInfo.Pcm).bitDepth,
        )

        volumePct.value = 50
        runCurrent()

        assertEquals(TelemetryStatus.INACTIVE, collector.telemetry.value.isBitPerfect)
        assertEquals(
            false,
            collector.telemetry.value.bitPerfectDiagnostics?.isSoftwareVolumeAtUnity,
        )
    }

    @Test
    fun `given audiophile direct path when sink falls back to float then collector does not overclaim bit perfect`() = runTest {
        val currentFormat = MutableStateFlow<AudioFormatInfo?>(null)
        val pathReport = MutableStateFlow<PipelinePathReport?>(null)
        val state = MutableStateFlow(EnginePlaybackState.IDLE)
        val activeEngineType = MutableStateFlow(EngineType.AUDIOPHILE)
        val engine = mockk<AudioEngineManager>()

        every { engine.currentFormat } returns currentFormat
        every { engine.pathReport } returns pathReport
        every { engine.state } returns state
        every { engine.activeEngineType } returns activeEngineType
        every { engine.currentUri } returns MutableStateFlow(null)

        // When usedFloatFallback=true the HAL rejected the source bit-depth and
        // fell back to PCM_FLOAT. AudioPathValidator would derive DIRECT_SUPPORTED
        // (not DIRECT_BIT_PERFECT) in this scenario. isBitPerfect must be false.
        val collector = AudioTelemetryCollector(
            engine = engine,
            audioPathValidator = mockValidator(
                pathState = MutableStateFlow(
                    AudioPathState(pathStatus = AudioPathStatus.DIRECT_SUPPORTED)
                )
            ),
            usbVolumeController = mockVolumeController(),
            appScope = CoroutineScope(
                backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)
            ),
        )

        currentFormat.value = AudioFormatInfo(
            sampleRateHz = 192_000,
            channelCount = 2,
            sourceBitDepth = 24,
            androidPcmEncoding = android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED,
            bytesPerSample = 3,
            durationMs = 0L,
            bitrateKbps = 0,
            codec = AudioCodec.FLAC,
        )
        pathReport.value = PipelinePathReport(
            usedDirectFlag = true,
            usedFloatFallback = true,
            encoding = android.media.AudioFormat.ENCODING_PCM_FLOAT,
            sampleRateHz = 192_000,
            channelMask = 0,
            bufferFrames = 0,
            nativeOutputSampleRateHz = 48_000,
            framesPerBuffer = 0,
            routedDeviceType = 0,
            routedDeviceName = null,
            audioSessionId = 0,
        )
        state.value = EnginePlaybackState.PLAYING

        runCurrent()

        val snapshot = collector.telemetry.value
        assertEquals(TelemetryStatus.ACTIVE, snapshot.isDirectPlayback)
        // DIRECT_SUPPORTED means FLAG_DIRECT was granted but not bit-perfect —
        // pathStatus != DIRECT_BIT_PERFECT so isBitPerfect must be false.
        assertEquals(TelemetryStatus.ACTIVE_UNCONFIRMED, snapshot.isBitPerfect)
    }

    @Test
    fun `given enhanced libusb path when telemetry is built then direct remains active but bit perfect is inactive`() = runTest {
        val currentFormat = MutableStateFlow<AudioFormatInfo?>(null)
        val pathReport = MutableStateFlow<PipelinePathReport?>(null)
        val state = MutableStateFlow(EnginePlaybackState.IDLE)
        val activeEngineType = MutableStateFlow(EngineType.AUDIOPHILE)
        val engine = mockk<AudioEngineManager>()

        every { engine.currentFormat } returns currentFormat
        every { engine.pathReport } returns pathReport
        every { engine.state } returns state
        every { engine.activeEngineType } returns activeEngineType
        every { engine.currentUri } returns MutableStateFlow(null)

        val collector = AudioTelemetryCollector(
            engine = engine,
            audioPathValidator = mockValidator(
                pathState = MutableStateFlow(
                    AudioPathState(pathStatus = AudioPathStatus.DIRECT_SUPPORTED)
                )
            ),
            usbVolumeController = mockVolumeController(),
            appScope = CoroutineScope(
                backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)
            ),
        )

        currentFormat.value = AudioFormatInfo(
            sampleRateHz = 48_000,
            channelCount = 2,
            sourceBitDepth = 16,
            androidPcmEncoding = android.media.AudioFormat.ENCODING_PCM_16BIT,
            bytesPerSample = 2,
            durationMs = 0L,
            bitrateKbps = 256,
            codec = AudioCodec.AAC,
        )
        pathReport.value = PipelinePathReport(
            usedDirectFlag = true,
            usedFloatFallback = false,
            encoding = android.media.AudioFormat.ENCODING_PCM_32BIT,
            sampleRateHz = 96_000,
            channelMask = 2,
            bufferFrames = 0,
            nativeOutputSampleRateHz = 96_000,
            framesPerBuffer = 8,
            routedDeviceType = android.hardware.usb.UsbConstants.USB_CLASS_AUDIO,
            routedDeviceName = "USB DAC",
            audioSessionId = 0,
            usbPcmSubslotBitDepth = 32,
            usbPcmValidBitDepth = 24,
            sueInfo = SueInfo(
                enabled = true,
                isActive = true,
                isLossy = true,
                isProvisioned = true,
                intensityProfile = "MODERATE",
                codecDisplayName = "AAC",
            ),
        )
        state.value = EnginePlaybackState.PLAYING

        runCurrent()

        val snapshot = collector.telemetry.value
        assertEquals(TelemetryStatus.ACTIVE, snapshot.isDirectPlayback)
        assertEquals(TelemetryStatus.INACTIVE, snapshot.isBitPerfect)
        assertEquals(24, (snapshot.streamInfo as OutputStreamInfo.Pcm).bitDepth)

        pathReport.value = pathReport.value?.copy(usbPcmValidBitDepth = 32)
        runCurrent()

        assertEquals(
            32,
            (collector.telemetry.value.streamInfo as OutputStreamInfo.Pcm).bitDepth,
        )
    }

    @Test
    fun `given active A2DP route when telemetry is built then diagnostics expose Bluetooth family`() = runTest {
        val format = MutableStateFlow<AudioFormatInfo?>(null)
        val report = MutableStateFlow<PipelinePathReport?>(null)
        val state = MutableStateFlow(EnginePlaybackState.IDLE)
        val engineType = MutableStateFlow(EngineType.AUDIOPHILE)
        val engine = mockk<AudioEngineManager>()

        every { engine.currentFormat } returns format
        every { engine.pathReport } returns report
        every { engine.state } returns state
        every { engine.activeEngineType } returns engineType
        every { engine.currentUri } returns MutableStateFlow(null)

        val collector = AudioTelemetryCollector(
            engine = engine,
            audioPathValidator = mockValidator(
                MutableStateFlow(
                    AudioPathState(
                        activeDeviceType = android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                        activeDeviceName = "OnePlus Buds 4",
                        pathStatus = AudioPathStatus.RESAMPLED,
                    )
                )
            ),
            usbVolumeController = mockVolumeController(),
            appScope = CoroutineScope(
                backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)
            ),
        )

        format.value = AudioFormatInfo(
            sampleRateHz = 96_000,
            channelCount = 2,
            sourceBitDepth = 24,
            androidPcmEncoding = android.media.AudioFormat.ENCODING_PCM_32BIT,
            bytesPerSample = 4,
            durationMs = 0L,
            bitrateKbps = 0,
            codec = AudioCodec.FLAC,
        )
        report.value = PipelinePathReport(
            usedDirectFlag = false,
            usedFloatFallback = false,
            encoding = android.media.AudioFormat.ENCODING_PCM_32BIT,
            sampleRateHz = 96_000,
            channelMask = 0,
            bufferFrames = 0,
            nativeOutputSampleRateHz = 48_000,
            framesPerBuffer = 0,
            routedDeviceType = android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            routedDeviceName = "OnePlus Buds 4",
            audioSessionId = 41,
        )
        state.value = EnginePlaybackState.PLAYING

        runCurrent()

        val diagnostics = collector.telemetry.value.bitPerfectDiagnostics
        assertEquals(OutputRouteKind.BLUETOOTH, diagnostics?.outputRouteKind)
        assertEquals("OnePlus Buds 4", diagnostics?.activeDeviceName)
    }
}
