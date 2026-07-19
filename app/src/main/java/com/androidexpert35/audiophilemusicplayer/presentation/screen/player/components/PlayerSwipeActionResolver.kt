package com.androidexpert35.audiophilemusicplayer.presentation.screen.player.components

import kotlin.math.abs

/**
 * Resolves whether a finished horizontal player drag should change the queue item.
 *
 * A fast release takes precedence over the remaining drag offset. This mirrors the
 * direction a listener perceives at lift-off and avoids selecting the previous item
 * after a short leftward movement followed by a deliberate fast rightward flick.
 */
internal object PlayerSwipeActionResolver {

    /**
     * Determines the transport action for a completed drag.
     *
     * @param horizontalOffsetPx Signed horizontal offset when the pointer was lifted.
     *   Negative values indicate a right-to-left drag.
     * @param velocityPxPerSecond Signed horizontal release velocity. Negative values
     *   indicate movement towards the next track.
     * @param distanceThresholdPx Minimum absolute offset needed for a non-flick swipe.
     * @param velocityThresholdPx Minimum absolute velocity needed for a flick.
     * @return The corresponding queue action, or `null` when the gesture was too small.
     */
    fun resolve(
        horizontalOffsetPx: Float,
        velocityPxPerSecond: Float,
        distanceThresholdPx: Float,
        velocityThresholdPx: Float
    ): PlayerSwipeAction? {
        val direction = when {
            abs(velocityPxPerSecond) >= velocityThresholdPx -> velocityPxPerSecond
            abs(horizontalOffsetPx) >= distanceThresholdPx -> horizontalOffsetPx
            else -> return null
        }
        return if (direction < 0f) PlayerSwipeAction.NEXT else PlayerSwipeAction.PREVIOUS
    }
}
