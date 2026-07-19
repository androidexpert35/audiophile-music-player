package com.androidexpert35.audiophilemusicplayer.presentation.screen.player.components

/**
 * Identifies the queue operation selected by a completed horizontal player swipe.
 */
internal enum class PlayerSwipeAction {
    /** Advances playback after a right-to-left swipe. */
    NEXT,

    /** Returns playback after a left-to-right swipe. */
    PREVIOUS
}
