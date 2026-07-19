package com.androidexpert35.audiophilemusicplayer.presentation.navigation.overlay

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dispatches buffered player-overlay requests without mutating the navigation stack.
 */
@Singleton
class PlayerOverlayManagerImpl @Inject constructor() : PlayerOverlayManager {

    private val requests = Channel<Unit>(capacity = Channel.BUFFERED)
    override val openRequests = requests.receiveAsFlow()

    override fun open() {
        requests.trySend(Unit)
    }
}
