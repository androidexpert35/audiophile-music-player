package com.androidexpert35.audiophilemusicplayer.data.repository

import com.androidexpert35.audiophilemusicplayer.data.local.dao.LibraryIndexDao
import com.androidexpert35.audiophilemusicplayer.data.local.entity.ArtistEntity
import com.androidexpert35.audiophilemusicplayer.data.remote.api.DeezerApiService
import com.androidexpert35.audiophilemusicplayer.data.remote.dto.DeezerArtistDto
import com.androidexpert35.audiophilemusicplayer.data.remote.dto.DeezerArtistSearchResponse
import com.tony.coreui.domain.resource.Resource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteImageRepositoryImplTest {

    private val deezerApiService = mockk<DeezerApiService>()
    private val libraryIndexDao = mockk<LibraryIndexDao>()

    @Test
    fun `given cached artist URL when requested then Deezer is not queried`() = runTest {
        val cachedUrl = "https://cdn.example/cached.jpg"
        coEvery { libraryIndexDao.getArtistByName("Michael Jackson") } returns
            artistEntity(remoteImageUrl = cachedUrl)
        val repository = repository()

        val result = repository.getArtistImageUrl("Michael Jackson")

        assertEquals(cachedUrl, (result as Resource.Success).data)
        coVerify(exactly = 0) { deezerApiService.searchArtist(any()) }
    }

    @Test
    fun `given unknown artist sentinel when requested then remote lookup is skipped`() = runTest {
        val repository = repository()

        val result = repository.getArtistImageUrl("Unknown Artist")

        assertTrue(result is Resource.Error)
        coVerify(exactly = 0) { libraryIndexDao.getArtistByName(any()) }
        coVerify(exactly = 0) { deezerApiService.searchArtist(any()) }
    }

    @Test
    fun `given wrong first Deezer result when requested then exact match is cached`() = runTest {
        val correctUrl = "https://cdn.example/michael.jpg"
        coEvery { libraryIndexDao.getArtistByName("Michael Jackson") } returns
            artistEntity(remoteImageUrl = null)
        coEvery { deezerApiService.searchArtist("Michael Jackson") } returns
            DeezerArtistSearchResponse(
                data = listOf(
                    DeezerArtistDto(
                        id = 1L,
                        name = "Michael Jackson Tribute",
                        pictureXl = "https://cdn.example/wrong.jpg",
                        fanCount = 2_000L
                    ),
                    DeezerArtistDto(
                        id = 2L,
                        name = "Michael Jackson",
                        pictureXl = correctUrl,
                        fanCount = 1_000L
                    )
                )
            )
        coEvery {
            libraryIndexDao.updateArtistRemoteImageUrl("Michael Jackson", correctUrl)
        } returns Unit
        val repository = repository()

        val result = repository.getArtistImageUrl("Michael Jackson")

        assertEquals(correctUrl, (result as Resource.Success).data)
        coVerify(exactly = 1) {
            libraryIndexDao.updateArtistRemoteImageUrl("Michael Jackson", correctUrl)
        }
    }

    @Test
    fun `given no exact Deezer result when requested then wrong URL is not cached`() = runTest {
        coEvery { libraryIndexDao.getArtistByName("Jackson") } returns
            artistEntity(name = "Jackson", remoteImageUrl = null)
        coEvery { deezerApiService.searchArtist("Jackson") } returns
            DeezerArtistSearchResponse(
                data = listOf(
                    DeezerArtistDto(
                        id = 1L,
                        name = "Michael Jackson",
                        pictureXl = "https://cdn.example/wrong.jpg"
                    )
                )
            )
        val repository = repository()

        val result = repository.getArtistImageUrl("Jackson")

        assertTrue(result is Resource.Error)
        coVerify(exactly = 0) {
            libraryIndexDao.updateArtistRemoteImageUrl(any(), any())
        }
    }

    private fun repository(): RemoteImageRepositoryImpl = RemoteImageRepositoryImpl(
        deezerApiService = deezerApiService,
        libraryIndexDao = libraryIndexDao,
        ioDispatcher = UnconfinedTestDispatcher()
    )

    private fun artistEntity(
        name: String = "Michael Jackson",
        remoteImageUrl: String?
    ): ArtistEntity = ArtistEntity(
        id = 10L,
        name = name,
        albumCount = 1,
        trackCount = 1,
        totalDurationMs = 1L,
        remoteImageUrl = remoteImageUrl
    )
}
