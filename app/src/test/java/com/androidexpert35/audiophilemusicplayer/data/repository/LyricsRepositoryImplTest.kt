package com.androidexpert35.audiophilemusicplayer.data.repository

import com.androidexpert35.audiophilemusicplayer.data.local.dao.LyricsCacheDao
import com.androidexpert35.audiophilemusicplayer.data.local.entity.LyricsCacheEntity
import com.androidexpert35.audiophilemusicplayer.data.remote.api.LrcLibApiService
import com.androidexpert35.audiophilemusicplayer.data.remote.dto.LrcLibLyricsDto
import com.androidexpert35.audiophilemusicplayer.domain.model.lyrics.Lyrics
import com.tony.coreui.domain.resource.Resource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.util.concurrent.TimeUnit

/**
 * Behavioural tests for [LyricsRepositoryImpl], focused on the failure modes that
 * made every track report "lyrics unavailable": a Cloudflare rejection being
 * cached as a permanent miss, and `/api/get` being strict enough that ordinary
 * local-file metadata drift produced a 404 with no fallback.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LyricsRepositoryImplTest {

    private val lrcLibApiService = mockk<LrcLibApiService>()
    private val lyricsCacheDao = mockk<LyricsCacheDao>(relaxed = true)

    @Test
    fun `given server error when fetching then error is returned and nothing is cached`() = runTest {
        coEvery { lyricsCacheDao.getByKey(any()) } returns null
        coEvery { lrcLibApiService.getLyrics(any(), any(), any(), any()) } returns
            errorResponse(code = 520)

        val result = repository().getLyrics("Yellow", "Coldplay", "Parachutes", 266)

        assertTrue(result is Resource.Error)
        coVerify(exactly = 0) { lyricsCacheDao.upsert(any()) }
    }

    @Test
    fun `given exact lookup misses when searching then closest synced candidate wins`() = runTest {
        coEvery { lyricsCacheDao.getByKey(any()) } returns null
        coEvery { lrcLibApiService.getLyrics(any(), any(), any(), any()) } returns errorResponse(404)
        coEvery { lrcLibApiService.searchLyrics("Yellow", "Coldplay") } returns Response.success(
            listOf(
                candidate(duration = 320.0, syncedLyrics = "[00:20.00]far off\n"),
                candidate(duration = 267.0, syncedLyrics = SYNCED_LRC),
            )
        )

        val result = repository().getLyrics("Yellow (Remastered 2011)", "Coldplay", "Parachutes", 266)

        val lyrics = (result as Resource.Success).data
        assertEquals(2, lyrics.lines.size)
    }

    @Test
    fun `given only distant synced candidate when searching then synced timings are dropped`() = runTest {
        coEvery { lyricsCacheDao.getByKey(any()) } returns null
        coEvery { lrcLibApiService.getLyrics(any(), any(), any(), any()) } returns errorResponse(404)
        coEvery { lrcLibApiService.searchLyrics(any(), any()) } returns Response.success(
            listOf(candidate(duration = 400.0, syncedLyrics = SYNCED_LRC))
        )

        val result = repository().getLyrics("Yellow", "Coldplay", "Parachutes", 266)

        val lyrics = (result as Resource.Success).data
        assertTrue(lyrics.lines.isEmpty())
        assertEquals(PLAIN_LYRICS, lyrics.plainLyrics)
    }

    @Test
    fun `given candidates by another artist when searching then no lyrics are returned`() = runTest {
        coEvery { lyricsCacheDao.getByKey(any()) } returns null
        coEvery { lrcLibApiService.getLyrics(any(), any(), any(), any()) } returns errorResponse(404)
        coEvery { lrcLibApiService.searchLyrics(any(), any()) } returns Response.success(
            listOf(candidate(artistName = "Some Tribute Band", duration = 266.0))
        )

        val result = repository().getLyrics("Yellow", "Coldplay", "Parachutes", 266)

        assertTrue((result as Resource.Success).data.lines.isEmpty())
        assertNull(result.data.plainLyrics)
        coVerify { lyricsCacheDao.upsert(match { it.notFound }) }
    }

    @Test
    fun `given expired not-found sentinel when fetching then the API is queried again`() = runTest {
        coEvery { lyricsCacheDao.getByKey(any()) } returns LyricsCacheEntity(
            cacheKey = "yellow|coldplay|parachutes|266",
            syncedLyricsRaw = null,
            plainLyrics = null,
            isInstrumental = false,
            notFound = true,
            fetchedAtMs = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30),
        )
        coEvery { lrcLibApiService.getLyrics(any(), any(), any(), any()) } returns
            Response.success(candidate(duration = 266.0, syncedLyrics = SYNCED_LRC))

        val result = repository().getLyrics("Yellow", "Coldplay", "Parachutes", 266)

        assertEquals(2, (result as Resource.Success).data.lines.size)
        coVerify(exactly = 1) { lrcLibApiService.getLyrics(any(), any(), any(), any()) }
    }

    @Test
    fun `given fresh not-found sentinel when fetching then no network call is made`() = runTest {
        coEvery { lyricsCacheDao.getByKey(any()) } returns LyricsCacheEntity(
            cacheKey = "yellow|coldplay|parachutes|266",
            syncedLyricsRaw = null,
            plainLyrics = null,
            isInstrumental = false,
            notFound = true,
            fetchedAtMs = System.currentTimeMillis(),
        )

        val result = repository().getLyrics("Yellow", "Coldplay", "Parachutes", 266)

        assertEquals(Lyrics(emptyList(), null, false), (result as Resource.Success).data)
        coVerify(exactly = 0) { lrcLibApiService.getLyrics(any(), any(), any(), any()) }
        coVerify(exactly = 0) { lrcLibApiService.searchLyrics(any(), any()) }
    }

    @Test
    fun `given ft credit and Pt marker when searching then both are stripped from the query`() = runTest {
        coEvery { lyricsCacheDao.getByKey(any()) } returns null
        coEvery { lrcLibApiService.getLyrics(any(), any(), any(), any()) } returns errorResponse(404)
        coEvery { lrcLibApiService.searchLyrics("Veleno 7", "Gemitaiz") } returns Response.success(
            listOf(
                candidate(
                    trackName = "Veleno 7",
                    artistName = "Gemitaiz",
                    duration = 174.0,
                    syncedLyrics = SYNCED_LRC,
                )
            )
        )

        val result = repository()
            .getLyrics("Veleno pt.7 ft. MadMan", "Gemitaiz", "Veleno", 174)

        assertEquals(2, (result as Resource.Success).data.lines.size)
        coVerify { lrcLibApiService.searchLyrics("Veleno 7", "Gemitaiz") }
    }

    @Test
    fun `given Part as a real word when searching then the title is left intact`() = runTest {
        coEvery { lyricsCacheDao.getByKey(any()) } returns null
        coEvery { lrcLibApiService.getLyrics(any(), any(), any(), any()) } returns errorResponse(404)
        coEvery { lrcLibApiService.searchLyrics(any(), any()) } returns Response.success(emptyList())

        repository().getLyrics("Part of Me", "Katy Perry", "Teenage Dream", 216)

        coVerify { lrcLibApiService.searchLyrics("Part of Me", "Katy Perry") }
    }

    @Test
    fun `given unknown artist tag when fetching then only the title search runs`() = runTest {
        coEvery { lyricsCacheDao.getByKey(any()) } returns null
        coEvery { lrcLibApiService.searchLyrics("Yellow", null) } returns Response.success(
            listOf(candidate(duration = 266.0, syncedLyrics = SYNCED_LRC))
        )

        val result = repository().getLyrics("Yellow", "Unknown Artist", "Unknown Album", 266)

        assertEquals(2, (result as Resource.Success).data.lines.size)
        coVerify(exactly = 0) { lrcLibApiService.getLyrics(any(), any(), any(), any()) }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun repository() = LyricsRepositoryImpl(
        lrcLibApiService = lrcLibApiService,
        lyricsCacheDao = lyricsCacheDao,
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    private fun <T> errorResponse(code: Int): Response<T> =
        Response.error(code, "".toResponseBody(null))

    private fun candidate(
        trackName: String = "Yellow",
        artistName: String = "Coldplay",
        duration: Double,
        syncedLyrics: String? = null,
    ) = LrcLibLyricsDto(
        id = 1,
        trackName = trackName,
        artistName = artistName,
        duration = duration,
        instrumental = false,
        plainLyrics = PLAIN_LYRICS,
        syncedLyrics = syncedLyrics,
    )

    private companion object {
        const val PLAIN_LYRICS = "first line\nsecond line"
        const val SYNCED_LRC = "[00:12.00]first line\n[00:18.50]second line\n"
    }
}
