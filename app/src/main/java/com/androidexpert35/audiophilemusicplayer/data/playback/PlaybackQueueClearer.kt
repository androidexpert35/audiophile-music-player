package com.androidexpert35.audiophilemusicplayer.data.playback

import androidx.media3.common.Player

/**
 * Removes every queued item except the one currently being played.
 *
 * Both manual queue clearing and the task-removal preference use this boundary so the active
 * decoder and its playhead remain untouched. Future items are removed first, followed by played
 * items, leaving the current media item as the sole entry in the Media3 playlist.
 */
internal object PlaybackQueueClearer {

    /**
     * Removes all non-current media items from [player].
     *
     * @return `true` when a current media item remains in the queue; `false` when the player had
     *   no resolvable active item and its queue was cleared instead.
     */
    fun retainCurrentMediaItem(player: Player): Boolean {
        val itemCount = player.mediaItemCount
        val currentIndex = player.currentMediaItemIndex
        if (currentIndex !in 0 until itemCount) {
            player.clearMediaItems()
            return false
        }

        removalRanges(itemCount, currentIndex).forEach { range ->
            player.removeMediaItems(range.first, range.last + 1)
        }
        return true
    }

    /** Returns end-inclusive removal ranges in the safe order for retaining [currentIndex]. */
    internal fun removalRanges(itemCount: Int, currentIndex: Int): List<IntRange> {
        if (itemCount <= 0 || currentIndex !in 0 until itemCount) return emptyList()
        return buildList {
            if (currentIndex < itemCount - 1) add((currentIndex + 1)..<itemCount)
            if (currentIndex > 0) add(0..<currentIndex)
        }
    }
}
