package com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile

import com.androidexpert35.audiophilemusicplayer.data.playback.engine.EnginePlaybackState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
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
        every { releaseUsbSinkNow() } returns true
    }

    private val engine = AudiophileEngine(core)

    @Test
    fun `given audiophile output when paused then USB claim is kept for the idle scheduler`() {
        // A pause must NOT tear down the USB sink immediately: releasing the
        // claim makes the DAC re-enumerate on every pause/resume (system volume
        // panel flashes, slow resume). The core engine's idle-sink scheduler
        // owns the deferred release instead.
        engine.pause()

        verify(exactly = 1) { core.pause() }
        verify(exactly = 0) { core.releaseUsbSinkNow() }
    }

    @Test
    fun `given focus-loss hook when invoked then USB sink is released immediately`() {
        engine.releaseUsbSinkNow()

        verify(exactly = 1) { core.releaseUsbSinkNow() }
    }
}
