package com.androidexpert35.audiophilemusicplayer.data.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit tests for repeat traversal and shuffle-order resolution. */
class PlaybackQueueOrderResolverTest {

    @Test
    fun `given repeat one when current track ends then current index is selected`() {
        val nextIndex = PlaybackQueueOrderResolver.nextIndex(
            queueSize = 4,
            currentIndex = 2,
            repeatMode = Player.REPEAT_MODE_ONE,
        )

        assertEquals(2, nextIndex)
    }

    @Test
    fun `given repeat all when final track ends then first index is selected`() {
        val nextIndex = PlaybackQueueOrderResolver.nextIndex(
            queueSize = 4,
            currentIndex = 3,
            repeatMode = Player.REPEAT_MODE_ALL,
        )

        assertEquals(0, nextIndex)
    }

    @Test
    fun `given repeat off when final track ends then no follower is selected`() {
        val nextIndex = PlaybackQueueOrderResolver.nextIndex(
            queueSize = 4,
            currentIndex = 3,
            repeatMode = Player.REPEAT_MODE_OFF,
        )

        assertNull(nextIndex)
    }

    @Test
    fun `given shuffle enabled when a track is playing then played prefix is preserved and remaining tracks stay unique`() {
        val queue = (1..5).map(::mediaItem)

        val shuffled = PlaybackQueueOrderResolver.shuffleUpcoming(
            playlist = queue,
            originalPlaylist = queue,
            currentIndex = 1,
            random = Random(42),
        )

        assertEquals(queue.take(2), shuffled.take(2))
        assertEquals(queue.map(MediaItem::mediaId).toSet(), shuffled.map(MediaItem::mediaId).toSet())
        assertEquals(queue.size, shuffled.size)
    }

    // The resolver traverses by mediaId only; a URI is deliberately omitted
    // because MediaItem.Builder.setUri(String) routes through android.net.Uri,
    // which is unavailable on the local JVM.
    private fun mediaItem(id: Int): MediaItem = MediaItem.Builder()
        .setMediaId(id.toString())
        .build()
}
