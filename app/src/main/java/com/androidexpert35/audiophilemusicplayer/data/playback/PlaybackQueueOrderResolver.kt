package com.androidexpert35.audiophilemusicplayer.data.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlin.random.Random

/**
 * Resolves queue traversal and the active shuffle order for the playback adapter.
 *
 * The adapter keeps its Media3 playlist in the currently effective traversal order.
 * This helper centralises the mode-dependent decisions so that the standard and
 * audiophile engines receive the same preloaded follower.
 */
internal object PlaybackQueueOrderResolver {

    /** Resolves the item that must follow [currentIndex] on an automatic transition. */
    fun nextIndex(
        queueSize: Int,
        currentIndex: Int,
        @Player.RepeatMode repeatMode: Int,
    ): Int? = when {
        queueSize == 0 || currentIndex !in 0 until queueSize -> null
        repeatMode == Player.REPEAT_MODE_ONE -> currentIndex
        currentIndex < queueSize - 1 -> currentIndex + 1
        repeatMode == Player.REPEAT_MODE_ALL -> 0
        else -> null
    }

    /**
     * Retains the played prefix and randomises each queue item that has not played yet.
     *
     * Queue media IDs are the stable track IDs assigned by [PlaybackController], so
     * they can safely identify items retained in the prefix.
     */
    fun shuffleUpcoming(
        playlist: List<MediaItem>,
        originalPlaylist: List<MediaItem>,
        currentIndex: Int,
        random: Random = Random.Default,
    ): List<MediaItem> {
        if (currentIndex !in playlist.indices) return playlist

        val playedPrefix = playlist.take(currentIndex + 1)
        val playedMediaIds = playedPrefix.map(MediaItem::mediaId).toSet()
        val shuffledUpcoming = originalPlaylist
            .filterNot { it.mediaId in playedMediaIds }
            .shuffled(random)

        return playedPrefix + shuffledUpcoming
    }
}
