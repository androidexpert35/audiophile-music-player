package com.androidexpert35.audiophilemusicplayer.data.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests the removal order used to retain uninterrupted current-track playback. */
class PlaybackQueueClearerTest {

    @Test
    fun `given played and upcoming tracks when clearing then future tracks are removed before played tracks`() {
        val ranges = PlaybackQueueClearer.removalRanges(itemCount = 5, currentIndex = 2)

        assertEquals(listOf(3..4, 0..1), ranges)
    }

    @Test
    fun `given current first track when clearing then only upcoming tracks are removed`() {
        val ranges = PlaybackQueueClearer.removalRanges(itemCount = 3, currentIndex = 0)

        assertEquals(listOf(1..2), ranges)
    }

    @Test
    fun `given current last track when clearing then only played tracks are removed`() {
        val ranges = PlaybackQueueClearer.removalRanges(itemCount = 3, currentIndex = 2)

        assertEquals(listOf(0..1), ranges)
    }
}
