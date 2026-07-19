package com.androidexpert35.audiophilemusicplayer.presentation.screen.player.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Tests queue-action selection for now-playing horizontal swipe gestures. */
class PlayerSwipeActionResolverTest {

    @Test
    fun `given a leftward drag past distance threshold when resolved then advances queue`() {
        val action = PlayerSwipeActionResolver.resolve(
            horizontalOffsetPx = -220f,
            velocityPxPerSecond = 0f,
            distanceThresholdPx = 180f,
            velocityThresholdPx = 800f
        )

        assertEquals(PlayerSwipeAction.NEXT, action)
    }

    @Test
    fun `given a rightward flick when resolved then returns to previous queue item`() {
        val action = PlayerSwipeActionResolver.resolve(
            horizontalOffsetPx = 40f,
            velocityPxPerSecond = 900f,
            distanceThresholdPx = 180f,
            velocityThresholdPx = 800f
        )

        assertEquals(PlayerSwipeAction.PREVIOUS, action)
    }

    @Test
    fun `given a fast release opposite to the residual offset when resolved then uses release direction`() {
        val action = PlayerSwipeActionResolver.resolve(
            horizontalOffsetPx = -30f,
            velocityPxPerSecond = 900f,
            distanceThresholdPx = 180f,
            velocityThresholdPx = 800f
        )

        assertEquals(PlayerSwipeAction.PREVIOUS, action)
    }

    @Test
    fun `given a small slow drag when resolved then keeps current queue item`() {
        val action = PlayerSwipeActionResolver.resolve(
            horizontalOffsetPx = -100f,
            velocityPxPerSecond = -300f,
            distanceThresholdPx = 180f,
            velocityThresholdPx = 800f
        )

        assertNull(action)
    }
}
