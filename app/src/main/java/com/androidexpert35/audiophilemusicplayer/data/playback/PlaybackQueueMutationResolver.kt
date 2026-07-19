package com.androidexpert35.audiophilemusicplayer.data.playback

/**
 * Applies queue moves while preserving the identity and position of the active item.
 */
internal object PlaybackQueueMutationResolver {

    /**
     * Moves a contiguous item range using Media3's post-removal insertion semantics.
     *
     * @param items Source queue.
     * @param fromIndex Inclusive first moved index.
     * @param toIndex Exclusive last moved index.
     * @param newIndex Insertion index after removing the source range.
     * @return A reordered copy, or the original copy when the supplied range is invalid.
     */
    fun <T> moveRange(
        items: List<T>,
        fromIndex: Int,
        toIndex: Int,
        newIndex: Int
    ): List<T> {
        if (fromIndex !in items.indices || toIndex !in (fromIndex + 1)..items.size) {
            return items.toList()
        }
        val movedCount = toIndex - fromIndex
        val insertionIndex = newIndex.coerceIn(0, items.size - movedCount)
        if (insertionIndex == fromIndex) return items.toList()

        val result = items.toMutableList()
        val moved = result.subList(fromIndex, toIndex).toList()
        result.subList(fromIndex, toIndex).clear()
        result.addAll(insertionIndex, moved)
        return result
    }

    /**
     * Resolves the active item's new index after a single queue item move.
     *
     * @param currentIndex Active index before the move.
     * @param fromIndex Source item index.
     * @param toIndex Final target index.
     * @return Active index after the move.
     */
    fun currentIndexAfterMove(
        currentIndex: Int,
        fromIndex: Int,
        toIndex: Int
    ): Int = when {
        currentIndex == fromIndex -> toIndex
        fromIndex < currentIndex && toIndex >= currentIndex -> currentIndex - 1
        fromIndex > currentIndex && toIndex <= currentIndex -> currentIndex + 1
        else -> currentIndex
    }
}
