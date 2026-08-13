package com.androidexpert35.audiophilemusicplayer.data.repository

import android.content.ContentResolver
import com.androidexpert35.audiophilemusicplayer.data.local.dao.LibraryIndexDao
import com.androidexpert35.audiophilemusicplayer.data.local.entity.AlbumEntity
import com.androidexpert35.audiophilemusicplayer.data.local.entity.ArtistEntity
import com.androidexpert35.audiophilemusicplayer.data.local.entity.LibraryIndexStateEntity
import com.androidexpert35.audiophilemusicplayer.data.local.entity.TrackEntity
import com.androidexpert35.audiophilemusicplayer.data.scanner.DsdFileScanner
import com.androidexpert35.audiophilemusicplayer.data.scanner.MediaStoreScanner
import com.androidexpert35.audiophilemusicplayer.data.scanner.MusicFolderScope
import com.tony.coreui.domain.resource.Resource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [MediaIndexRepositoryImpl].
 *
 * The behaviour under test is what the library scope means for the cached catalogue:
 * removing the last music folder must empty the index, a folder that is merely unreachable
 * right now must not, and a catalogue built from a different folder set must be treated as
 * stale rather than served.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MediaIndexRepositoryImplTest {

    private val scanner = mockk<MediaStoreScanner>()
    private val dsdFileScanner = mockk<DsdFileScanner>()
    private val musicFolderRegistry = mockk<MusicFolderRegistry>()
    private val libraryIndexDao = mockk<LibraryIndexDao>(relaxed = true)
    private val contentResolver = mockk<ContentResolver>(relaxed = true)

    @Test
    fun `given the last folder was removed when scanning then the cached catalogue is emptied`() =
        runTest {
            // No scope and nothing on record: the user deliberately dropped every folder, so
            // leaving the old tracks visible would keep serving revoked content.
            coEvery { musicFolderRegistry.getScopes() } returns emptyList()
            coEvery { musicFolderRegistry.hasStoredFolders() } returns false
            coEvery { musicFolderRegistry.folderSignature() } returns ""
            coEvery { scanner.scanAudioFilesForIndexing(any(), any()) } returns emptyList()
            coEvery { dsdFileScanner.scanDsdFiles(any()) } returns emptyList()

            val tracks = slot<List<TrackEntity>>()
            val albums = slot<List<AlbumEntity>>()
            val artists = slot<List<ArtistEntity>>()
            val state = slot<LibraryIndexStateEntity>()
            coEvery {
                libraryIndexDao.replaceIndexedLibrary(
                    capture(tracks),
                    capture(albums),
                    capture(artists),
                    capture(state),
                )
            } returns Unit

            val emissions = createRepository().scanAndIndexMedia().toList()

            coVerify(exactly = 1) { libraryIndexDao.replaceIndexedLibrary(any(), any(), any(), any()) }
            assertTrue(tracks.captured.isEmpty())
            assertTrue(albums.captured.isEmpty())
            assertTrue(artists.captured.isEmpty())
            assertEquals(0, state.captured.indexedTrackCount)
            assertTrue(emissions.none { it is Resource.Error })
        }

    @Test
    fun `given folders on record are unreachable when scanning then the index is left untouched`() =
        runTest {
            // Same empty scope, opposite cause: the card holding the folder is unmounted.
            // Wiping here would destroy a good catalogue over a temporary condition.
            coEvery { musicFolderRegistry.getScopes() } returns emptyList()
            coEvery { musicFolderRegistry.hasStoredFolders() } returns true

            val emissions = createRepository().scanAndIndexMedia().toList()

            coVerify(exactly = 0) { libraryIndexDao.replaceIndexedLibrary(any(), any(), any(), any()) }
            assertTrue(emissions.last() is Resource.Error)
        }

    @Test
    fun `given a granted folder when scanning then the index records the scope that produced it`() =
        runTest {
            coEvery { musicFolderRegistry.getScopes() } returns listOf(musicFolder())
            coEvery { musicFolderRegistry.hasStoredFolders() } returns true
            coEvery { musicFolderRegistry.folderSignature() } returns SIGNATURE
            coEvery { scanner.scanAudioFilesForIndexing(any(), any()) } returns emptyList()
            coEvery { dsdFileScanner.scanDsdFiles(any()) } returns emptyList()

            val state = slot<LibraryIndexStateEntity>()
            coEvery {
                libraryIndexDao.replaceIndexedLibrary(any(), any(), any(), capture(state))
            } returns Unit

            createRepository().scanAndIndexMedia().toList()

            assertEquals(SIGNATURE, state.captured.folderSignature)
            assertTrue(state.captured.isCompleted)
        }

    @Test
    fun `given the folder set changed since indexing then the cached library is not reusable`() =
        runTest {
            coEvery { libraryIndexDao.getLibraryIndexState() } returns indexState(SIGNATURE)
            coEvery { musicFolderRegistry.folderSignature() } returns "external_primary:Podcasts/"

            assertFalse(createRepository().isLibraryIndexed())
        }

    @Test
    fun `given the folder set is unchanged then the cached library is reusable`() = runTest {
        coEvery { libraryIndexDao.getLibraryIndexState() } returns indexState(SIGNATURE)
        coEvery { musicFolderRegistry.folderSignature() } returns SIGNATURE

        assertTrue(createRepository().isLibraryIndexed())
    }

    @Test
    fun `given an index predating folder scoping then it is rebuilt rather than served`() = runTest {
        // Empty signature marks a catalogue from the old whole-device scan — the one full of
        // messenger voice notes that folder scoping exists to keep out.
        coEvery { libraryIndexDao.getLibraryIndexState() } returns indexState(folderSignature = "")
        coEvery { musicFolderRegistry.folderSignature() } returns SIGNATURE

        assertFalse(createRepository().isLibraryIndexed())
    }

    private fun createRepository(): MediaIndexRepositoryImpl = MediaIndexRepositoryImpl(
        scanner = scanner,
        dsdFileScanner = dsdFileScanner,
        musicFolderRegistry = musicFolderRegistry,
        libraryIndexDao = libraryIndexDao,
        contentResolver = contentResolver,
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    private fun indexState(folderSignature: String): LibraryIndexStateEntity =
        LibraryIndexStateEntity(
            isCompleted = true,
            indexedTrackCount = 12,
            lastIndexedAtEpochMs = 1_000L,
            folderSignature = folderSignature,
        )

    private fun musicFolder(): MusicFolderScope = MusicFolderScope(
        treeUri = mockk(relaxed = true),
        volumeName = "external_primary",
        relativePath = "Music/DSD/",
        displayPath = "Music/DSD",
        storageLabel = "Internal storage",
    )

    private companion object {
        const val SIGNATURE = "external_primary:Music/DSD/"
    }
}
