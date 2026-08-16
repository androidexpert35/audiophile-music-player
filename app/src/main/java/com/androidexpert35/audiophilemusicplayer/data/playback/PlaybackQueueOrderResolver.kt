package com.androidexpert35.audiophilemusicplayer.data.playback

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
     * [uidOf] must yield an identity that is unique **per queue entry** (not per
     * track): with a track-level identity, a queue holding the same song twice
     * would silently drop the second copy from the upcoming section as soon as
     * the first copy entered the played prefix.
     */
    fun <T> shuffleUpcoming(
        playlist: List<T>,
        originalPlaylist: List<T>,
        currentIndex: Int,
        uidOf: (T) -> Any,
        random: Random = Random.Default,
    ): List<T> {
        if (currentIndex !in playlist.indices) return playlist

        val playedPrefix = playlist.take(currentIndex + 1)
        val playedUids = playedPrefix.map(uidOf).toSet()
        val shuffledUpcoming = originalPlaylist
            .filterNot { uidOf(it) in playedUids }
            .shuffled(random)

        return playedPrefix + shuffledUpcoming
    }
}
