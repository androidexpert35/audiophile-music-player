package com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile

import com.androidexpert35.audiophilemusicplayer.data.playback.engine.EnginePlaybackState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies pause-time resource ownership delegated by the audiophile strategy.
 */
class AudiophileEngineTest {

    private val core = mockk<BitPerfectPlaybackEngine> {
        every { state } returns MutableStateFlow(EnginePlaybackState.IDLE)
        every { positionMs } returns MutableStateFlow(0L)
        every { durationMs } returns MutableStateFlow(0L)
        every { currentUri } returns MutableStateFlow(null)
        every { currentFormat } returns MutableStateFlow(null)
        every { pathReport } returns MutableStateFlow(null)
        every { pause() } returns true
        coEvery { pauseAndReleaseOutput() } returns true
    }

    private val engine = AudiophileEngine(core)

    @Test
    fun `given audiophile output when paused then core owns the immediate release boundary`() {
        // The core performs pause and sink release in one audio-thread command;
        // the wrapper must not post a second, separately ordered release.
        engine.pause()

        verify(exactly = 1) { core.pause() }
    }

    @Test
    fun `given awaited release when invoked then core completes the pause boundary`() = runTest {
        val released = engine.pauseAndReleaseOutput()

        coVerify(exactly = 1) { core.pauseAndReleaseOutput() }
        assertTrue(released)
    }
}
