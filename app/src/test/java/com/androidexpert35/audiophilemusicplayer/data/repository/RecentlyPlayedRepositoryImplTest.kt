package com.androidexpert35.audiophilemusicplayer.data.repository

import com.androidexpert35.audiophilemusicplayer.data.local.dao.RecentlyPlayedDao
import com.tony.coreui.domain.resource.Resource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecentlyPlayedRepositoryImplTest {

    private val dao = mockk<RecentlyPlayedDao>()

    @Test
    fun `given playback starts when recorded then dao atomically increments track`() = runTest {
        coEvery { dao.recordPlaybackStart(trackId = 42L, playedAt = any()) } returns Unit
        val repository = RecentlyPlayedRepositoryImpl(
            recentlyPlayedDao = dao,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )

        val result = repository.recordPlayed(42L)

        assertTrue(result is Resource.Success)
        coVerify(exactly = 1) {
            dao.recordPlaybackStart(trackId = 42L, playedAt = any())
        }
    }

    @Test
    fun `given artist track ids when ranking observed then dao order is preserved`() = runTest {
        every {
            dao.observeMostPlayedTrackIds(trackIds = listOf(4L, 8L), limit = 5)
        } returns flowOf(listOf(8L, 4L))
        val repository = RecentlyPlayedRepositoryImpl(
            recentlyPlayedDao = dao,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )

        val result = repository.observeMostPlayedTrackIds(listOf(4L, 8L), 5).first()

        assertEquals(listOf(8L, 4L), result)
    }
}
