package com.androidexpert35.audiophilemusicplayer.data.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.SimpleBasePlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Play-Store crash reproduction (Media3 1.10.0):
 *
 * ```
 * java.lang.IllegalArgumentException
 *   at com.google.common.base.Preconditions.checkArgument(Preconditions.java:143)
 *   at androidx.media3.common.SimpleBasePlayer$State$Builder.setPlaylist(SimpleBasePlayer.java:598)
 *   at ….AudiophileSimpleBasePlayer.getState(AudiophileSimpleBasePlayer.kt:181)
 * ```
 *
 * `SimpleBasePlayer.State.Builder.setPlaylist` line 598 is
 * `checkArgument(uids.add(playlist.get(i).uid), "Duplicate MediaItemData UID in playlist")`.
 *
 * `AudiophileSimpleBasePlayer.getState()` derives each item's UID from
 * `MediaItem.mediaId` (falling back to the URI), and neither
 * `handleSetMediaItems` nor `handleAddMediaItems` deduplicates the queue. Any
 * queue that contains the same track twice — "add to queue" for a song already
 * queued, or a user playlist listing the same file twice — therefore crashes
 * the main thread on the next `invalidateState()` (typically an engine state
 * change), which matches the field stack traces exactly.
 */
class DuplicateQueueEntryCrashReproTest {

    /** Mirrors the UID derivation in AudiophileSimpleBasePlayer.getState(). */
    private fun mediaItemDataFor(item: MediaItem): SimpleBasePlayer.MediaItemData =
        SimpleBasePlayer.MediaItemData.Builder(
            item.mediaId.ifEmpty { item.localConfiguration?.uri?.toString().orEmpty() }
        )
            .setMediaItem(item)
            .build()

    @Test
    fun `same track queued twice reproduces the field IllegalArgumentException`() {
        val track = MediaItem.Builder().setMediaId("mediastore:12345").build()

        // Queue with the same song twice — exactly what the clearable-queue UI
        // allows via repeated "add to queue" of one track.
        val playlist = listOf(track, track).map(::mediaItemDataFor)

        val exception = assertThrows(IllegalArgumentException::class.java) {
            SimpleBasePlayer.State.Builder().setPlaylist(playlist).build()
        }
        assertEquals("Duplicate MediaItemData UID in playlist", exception.message)
    }

    @Test
    fun `unique per-entry uids accept the same track twice`() {
        val track = MediaItem.Builder().setMediaId("mediastore:12345").build()

        // The shipped fix: AudiophileSimpleBasePlayer wraps each queue entry in
        // a QueueEntry whose UID is mediaId + a monotonic per-insertion sequence
        // number, assigned once when the entry joins the queue.
        val playlist = listOf(track, track).mapIndexed { index, item ->
            SimpleBasePlayer.MediaItemData.Builder("${item.mediaId}#$index")
                .setMediaItem(item)
                .build()
        }

        val state = SimpleBasePlayer.State.Builder().setPlaylist(playlist).build()
        assertEquals(2, state.playlist.size)
    }
}
