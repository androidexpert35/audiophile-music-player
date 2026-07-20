package com.androidexpert35.audiophilemusicplayer.data.repository

import android.content.Context
import com.androidexpert35.audiophilemusicplayer.data.local.dao.LibraryIndexDao
import com.androidexpert35.audiophilemusicplayer.data.local.dao.LikedSongDao
import com.androidexpert35.audiophilemusicplayer.data.local.entity.LikedSongEntity
import com.androidexpert35.audiophilemusicplayer.data.local.entity.TrackEntity
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioFormat
import com.androidexpert35.audiophilemusicplayer.domain.model.library.PlaylistKind
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track
import com.tony.coreui.domain.resource.Resource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistRepositoryImplTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val context = mockk<Context>()
    private val likedSongDao = mockk<LikedSongDao>()
    private val libraryIndexDao = mockk<LibraryIndexDao>()

    @Test
    fun `given existing liked rows when playlists observed then favorites M3U is materialized first`() = runTest {
        val track = trackEntity(id = 7L, uri = "content://tracks/7")
        stubContext()
        every { likedSongDao.observeLikedSongIds() } returns flowOf(listOf(track.id))
        coEvery { libraryIndexDao.getTracksByIds(listOf(track.id)) } returns listOf(track)

        val playlists = repository(testScheduler).observePlaylists().first()

        val favorites = playlists.first()
        assertEquals(PlaylistKind.FAVORITES, favorites.kind)
        assertEquals(listOf(track.contentUri), favorites.trackUris)
        assertTrue(favoritesFile().readText().contains(track.contentUri))
    }

    @Test
    fun `given track added to favorites playlist then Room membership and M3U update together`() = runTest {
        val track = domainTrack(id = 9L, uri = "content://tracks/9")
        stubContext()
        coEvery { likedSongDao.getLikedSongIds() } returns emptyList()
        coEvery { likedSongDao.getLikedSongs() } returns emptyList()
        coEvery { likedSongDao.replaceLikedSongs(any()) } returns Unit

        val result = repository(testScheduler).addTrack(FAVORITES_ID, track)

        assertTrue(result is Resource.Success)
        assertTrue(favoritesFile().readText().contains(track.uri))
        coVerify(exactly = 1) {
            likedSongDao.replaceLikedSongs(
                match { rows -> rows.map(LikedSongEntity::trackId) == listOf(track.id) }
            )
        }
    }

    @Test
    fun `given unliked track when heart toggled then favorites playlist receives the track`() = runTest {
        val track = trackEntity(id = 11L, uri = "content://tracks/11")
        stubContext()
        coEvery { libraryIndexDao.getTracksByIds(listOf(track.id)) } returns listOf(track)
        coEvery { likedSongDao.getLikedSongs() } returns emptyList()
        coEvery { likedSongDao.replaceLikedSongs(any()) } returns Unit

        val result = repository(testScheduler).toggleLike(track.id)

        assertTrue(result is Resource.Success)
        assertTrue(favoritesFile().readText().contains(track.contentUri))
        coVerify(exactly = 1) {
            likedSongDao.replaceLikedSongs(
                match { rows -> rows.map(LikedSongEntity::trackId) == listOf(track.id) }
            )
        }
    }

    @Test
    fun `given partially liked album when album is liked then every track joins favorites`() = runTest {
        val first = trackEntity(id = 21L, uri = "content://tracks/21")
        val second = trackEntity(id = 22L, uri = "content://tracks/22")
        val currentRows = listOf(LikedSongEntity(trackId = first.id, likedAt = 1L))
        stubContext()
        coEvery { libraryIndexDao.getTracksByIds(listOf(first.id, second.id)) } returns
            listOf(first, second)
        coEvery { libraryIndexDao.getTracksByIds(listOf(first.id)) } returns listOf(first)
        coEvery { likedSongDao.getLikedSongs() } returns currentRows
        coEvery { likedSongDao.replaceLikedSongs(any()) } returns Unit

        val result = repository(testScheduler).setTracksLiked(
            trackIds = listOf(first.id, second.id),
            isLiked = true
        )

        assertTrue(result is Resource.Success)
        assertEquals(
            listOf(first.contentUri, second.contentUri),
            favoritesFile().readLines().filterNot { line -> line.isBlank() || line.startsWith("#") }
        )
        coVerify(exactly = 1) {
            likedSongDao.replaceLikedSongs(
                match { rows -> rows.map(LikedSongEntity::trackId) == listOf(first.id, second.id) }
            )
        }
    }

    @Test
    fun `given favorite detail order saved then Room order mirrors M3U order`() = runTest {
        val first = trackEntity(id = 1L, uri = "content://tracks/1")
        val second = trackEntity(id = 2L, uri = "content://tracks/2")
        stubContext()
        coEvery { likedSongDao.getLikedSongIds() } returns emptyList()
        coEvery { libraryIndexDao.getTracks() } returns listOf(first, second)
        coEvery { likedSongDao.replaceLikedSongs(any()) } returns Unit
        favoritesFile().parentFile?.mkdirs()
        favoritesFile().writeText("#EXTM3U\n#PLAYLIST:Liked Songs\n${first.contentUri}\n${second.contentUri}\n")

        val result = repository(testScheduler).replaceTracks(
            FAVORITES_ID,
            listOf(second.contentUri, first.contentUri)
        )

        assertTrue(result is Resource.Success)
        assertEquals(
            listOf(second.contentUri, first.contentUri),
            favoritesFile().readLines().filterNot { line -> line.isBlank() || line.startsWith("#") }
        )
        coVerify(exactly = 1) {
            likedSongDao.replaceLikedSongs(
                match { rows -> rows.map(LikedSongEntity::trackId) == listOf(2L, 1L) }
            )
        }
    }

    @Test
    fun `given standard playlist when deleted then playlist file is removed`() = runTest {
        stubContext()
        val repo = repository(testScheduler)
        val playlistId = (repo.createPlaylist("Road Trip") as Resource.Success).data.id

        val result = repo.deletePlaylist(playlistId)

        assertTrue(result is Resource.Success)
        assertTrue(!File(temporaryFolder.root, "playlists/$playlistId").exists())
    }

    @Test
    fun `given favorites playlist when deleted then request is rejected`() = runTest {
        stubContext()

        val result = repository(testScheduler).deletePlaylist(FAVORITES_ID)

        assertTrue(result is Resource.Error)
    }

    private fun repository(testScheduler: TestCoroutineScheduler): PlaylistRepositoryImpl =
        PlaylistRepositoryImpl(
            context = context,
            likedSongDao = likedSongDao,
            libraryIndexDao = libraryIndexDao,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler)
        )

    private fun stubContext() {
        every { context.filesDir } returns temporaryFolder.root
        every { context.getString(any()) } returns "Liked Songs"
    }

    private fun favoritesFile(): File = File(temporaryFolder.root, "playlists/$FAVORITES_ID")

    private fun trackEntity(id: Long, uri: String): TrackEntity = TrackEntity(
        id = id,
        title = "Track $id",
        artistId = 1L,
        artistName = "Artist",
        albumId = 2L,
        albumTitle = "Album",
        durationMs = 180_000L,
        contentUri = uri,
        filePath = "/music/$id.flac",
        trackNumber = id.toInt(),
        discNumber = 1,
        mimeType = "audio/flac",
        fileSizeBytes = 1_000L,
        dateAdded = 0L,
        sampleRateHz = 44_100,
        bitDepth = 16,
        channelCount = 2,
        isLossless = true
    )

    private fun domainTrack(id: Long, uri: String): Track = Track(
        id = id,
        title = "Track $id",
        artistName = "Artist",
        albumTitle = "Album",
        albumId = 2L,
        durationMs = 180_000L,
        uri = uri,
        trackNumber = id.toInt(),
        discNumber = 1,
        audioFormat = AudioFormat.UNKNOWN,
        fileSizeBytes = 1_000L,
        dateAdded = 0L
    )

    private companion object {
        const val FAVORITES_ID = "favorites.m3u"
    }
}
