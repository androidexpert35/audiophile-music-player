package com.androidexpert35.audiophilemusicplayer.data.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/** Tests active-item preservation and range movement for editable playback queues. */
class PlaybackQueueMutationResolverTest {

    @Test
    fun `given active item moves later when resolving then active index follows it`() {
        val resolvedIndex = PlaybackQueueMutationResolver.currentIndexAfterMove(
            currentIndex = 1,
            fromIndex = 1,
            toIndex = 3,
        )

        assertEquals(3, resolvedIndex)
    }

    @Test
    fun `given earlier item crosses active item when resolving then active index shifts left`() {
        val resolvedIndex = PlaybackQueueMutationResolver.currentIndexAfterMove(
            currentIndex = 3,
            fromIndex = 1,
            toIndex = 4,
        )

        assertEquals(2, resolvedIndex)
    }

    @Test
    fun `given later item crosses active item when resolving then active index shifts right`() {
        val resolvedIndex = PlaybackQueueMutationResolver.currentIndexAfterMove(
            currentIndex = 1,
            fromIndex = 4,
            toIndex = 0,
        )

        assertEquals(2, resolvedIndex)
    }

    @Test
    fun `given media range when moved then insertion uses post removal index`() {
        val reordered = PlaybackQueueMutationResolver.moveRange(
            items = listOf("a", "b", "c", "d", "e"),
            fromIndex = 1,
            toIndex = 3,
            newIndex = 3,
        )

        assertEquals(listOf("a", "d", "e", "b", "c"), reordered)
    }
}
