package com.androidexpert35.audiophilemusicplayer.presentation.navigation.overlay

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerOverlayManagerImplTest {

    @Test
    fun `given an active collector when open is requested then one signal is emitted`() = runTest {
        val manager = PlayerOverlayManagerImpl()
        val request = async(start = CoroutineStart.UNDISPATCHED) {
            manager.openRequests.first()
        }

        manager.open()

        assertEquals(Unit, request.await())
    }
}
