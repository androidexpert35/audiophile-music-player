package com.androidexpert35.audiophilemusicplayer.presentation.screen.player.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekBarStateResolverTest {

    @Test
    fun `given pending seek and stale external zero when resolving displayed position then pending target wins`() {
        val displayedPositionMs = SeekBarStateResolver.resolveDisplayedPositionMs(
            externalPositionMs = 0L,
            externalDurationMs = 240_000L,
            dragFraction = Float.NaN,
            pendingSeekPositionMs = 96_000L,
            pendingSeekDurationMs = 240_000L,
        )

        assertEquals(96_000L, displayedPositionMs)
    }

    @Test
    fun `given active drag when resolving displayed position then drag fraction overrides backend state`() {
        val displayedPositionMs = SeekBarStateResolver.resolveDisplayedPositionMs(
            externalPositionMs = 12_000L,
            externalDurationMs = 200_000L,
            dragFraction = 0.5f,
            pendingSeekPositionMs = SeekBarStateResolver.NoPendingSeekPositionMs,
            pendingSeekDurationMs = 0L,
        )

        assertEquals(100_000L, displayedPositionMs)
    }

    @Test
    fun `given external position reaches pending target when checking clear then pending seek is released`() {
        val shouldClear = SeekBarStateResolver.shouldClearPendingSeek(
            externalPositionMs = 95_600L,
            externalDurationMs = 240_000L,
            pendingSeekPositionMs = 96_000L,
            pendingSeekDurationMs = 240_000L,
        )

        assertTrue(shouldClear)
    }

    @Test
    fun `given backend has not yet reached pending target when checking clear then pending seek remains active`() {
        val shouldClear = SeekBarStateResolver.shouldClearPendingSeek(
            externalPositionMs = 0L,
            externalDurationMs = 240_000L,
            pendingSeekPositionMs = 96_000L,
            pendingSeekDurationMs = 240_000L,
        )

        assertFalse(shouldClear)
    }
}
