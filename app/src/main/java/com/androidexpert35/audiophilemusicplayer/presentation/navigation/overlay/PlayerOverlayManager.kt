package com.androidexpert35.audiophilemusicplayer.presentation.navigation.overlay

import kotlinx.coroutines.flow.Flow

/**
 * Coordinates one-shot requests to reveal Audiophile's persistent player overlay.
 */
interface PlayerOverlayManager {

    /** Emits once for every request to reveal the player overlay. */
    val openRequests: Flow<Unit>

    /** Requests that the persistent player overlay be revealed. */
    fun open()
}
