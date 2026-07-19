package com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile

import com.androidexpert35.audiophilemusicplayer.domain.model.audio.OutputRatePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSampleRatePlannerTest {

    @Test
    fun `given force 48k is active and enhancement is inactive when planning rates then force stage owns the only resample`() {
        val plan = PlaybackSampleRatePlanner.planPcmRates(
            sourceSampleRateHz = 44_100,
            sueWillBeActive = false,
            standaloneResampleTargetRateHz = OutputRatePolicy.FIXED_NON_USB_RATE_HZ,
        )

        assertEquals(OutputRatePolicy.FIXED_NON_USB_RATE_HZ, plan.sueOutputRateHz)
        assertEquals(OutputRatePolicy.FIXED_NON_USB_RATE_HZ, plan.finalOutputRateHz)
        assertEquals(OutputRatePolicy.FIXED_NON_USB_RATE_HZ, plan.standaloneResampleTargetRateHz)
        assertTrue(plan.needsStandaloneResampleStage)
    }

    @Test
    fun `given enhancement already owns resampling when force 48k is also requested then planner suppresses the extra force stage`() {
        val plan = PlaybackSampleRatePlanner.planPcmRates(
            sourceSampleRateHz = 44_100,
            sueWillBeActive = true,
            standaloneResampleTargetRateHz = OutputRatePolicy.FIXED_NON_USB_RATE_HZ,
        )

        assertEquals(OutputRatePolicy.FIXED_NON_USB_RATE_HZ, plan.sueOutputRateHz)
        assertEquals(OutputRatePolicy.FIXED_NON_USB_RATE_HZ, plan.finalOutputRateHz)
        assertFalse(plan.needsStandaloneResampleStage)
    }
}

