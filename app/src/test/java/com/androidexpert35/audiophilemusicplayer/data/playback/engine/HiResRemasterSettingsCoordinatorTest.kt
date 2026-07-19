package com.androidexpert35.audiophilemusicplayer.data.playback.engine

import android.util.Log
import com.androidexpert35.audiophilemusicplayer.data.playback.native_.AudioFormatInfo
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioCodec
import com.androidexpert35.audiophilemusicplayer.domain.repository.SettingsRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HiResRemasterSettingsCoordinatorTest {

    private val settingsRepository = mockk<SettingsRepository>()
    private val engineManager = mockk<AudioEngineManager>(relaxed = true)

    // The coordinator logs on start(); android.util.Log is not available on the
    // local JVM, so its statics are stubbed for the duration of each test.
    @Before
    fun stubAndroidLog() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
    }

    @After
    fun restoreAndroidLog() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `given coordinator starts when hi res preference changes then active track reloads once per distinct change`() = runTest {
        val hiResEnabledFlow = MutableStateFlow(true)
        every { settingsRepository.observeHiResRemasterEnabled() } returns hiResEnabledFlow
        // The coordinator only reloads when the active track is a lossless source
        // below native hi-res quality — expose a CD-quality FLAC so the reload
        // path is actually eligible.
        every { engineManager.currentFormat } returns MutableStateFlow(
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

        val coordinator = HiResRemasterSettingsCoordinator(
            settingsRepository = settingsRepository,
            engineManager = engineManager,
            // Cancellation-tied to backgroundScope but dispatched unconfined:
            // plain backgroundScope coroutines never run on the 1.10.x test
            // scheduler when no foreground work is pending.
            appScope = CoroutineScope(
                backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)
            ),
        )

        coordinator.start()
        advanceUntilIdle()

        verify(exactly = 0) { engineManager.reloadCurrentTrack() }

        hiResEnabledFlow.value = false
        advanceUntilIdle()

        verify(exactly = 1) { engineManager.reloadCurrentTrack() }

        hiResEnabledFlow.value = false
        advanceUntilIdle()

        verify(exactly = 1) { engineManager.reloadCurrentTrack() }
    }
}

