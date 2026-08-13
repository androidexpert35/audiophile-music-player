package com.androidexpert35.audiophilemusicplayer.data.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [M3uFileScanner.TrackPathIndex], the path-matching core of the `.m3u` import
 * feature. The `DocumentsContract` tree walk itself is not covered here — like [DsdFileScanner],
 * it requires the Android framework and is exercised manually/instrumented instead.
 */
class M3uFileScannerTest {

    @Test
    fun `given an absolute MediaStore path entry when resolved then it matches exactly`() {
        val index = M3uFileScanner.TrackPathIndex(
            listOf(scannedFile(filePath = "/storage/emulated/0/Music/Artist/Song.flac", contentUri = "content://tracks/1"))
        )

        val resolved = index.resolve("/storage/emulated/0/Music/Artist/Song.flac", parentDisplayPath = "Music/Playlist")

        assertEquals("content://tracks/1", resolved)
    }

    @Test
    fun `given a relative entry when resolved then it is anchored to the playlist's own folder`() {
        val index = M3uFileScanner.TrackPathIndex(
            listOf(scannedFile(filePath = "/storage/emulated/0/Music/Artist/Song.flac", contentUri = "content://tracks/1"))
        )

        val resolved = index.resolve("../Artist/Song.flac", parentDisplayPath = "Music/Playlist")

        // "Music/Playlist/../Artist/Song.flac" collapses to "Music/Artist/Song.flac", whose
        // segment tail matches the absolute candidate's tail via the suffix match.
        assertEquals("content://tracks/1", resolved)
    }

    @Test
    fun `given a DSD candidate with a grant-relative path when resolved by a shorter entry then it still matches`() {
        val index = M3uFileScanner.TrackPathIndex(
            listOf(scannedFile(filePath = "Music/DSD/Track.dsf", contentUri = "content://dsd/1"))
        )

        val resolved = index.resolve("Track.dsf", parentDisplayPath = "Music/DSD")

        assertEquals("content://dsd/1", resolved)
    }

    @Test
    fun `given an entry with no path match when resolved by a unique filename then it falls back to that candidate`() {
        val index = M3uFileScanner.TrackPathIndex(
            listOf(scannedFile(filePath = "/storage/emulated/0/Other/Unrelated/Song.flac", contentUri = "content://tracks/1"))
        )

        val resolved = index.resolve("Different/Folder/Song.flac", parentDisplayPath = "")

        assertEquals("content://tracks/1", resolved)
    }

    @Test
    fun `given two candidates sharing a filename when resolved then the ambiguous entry is left unresolved`() {
        val index = M3uFileScanner.TrackPathIndex(
            listOf(
                scannedFile(filePath = "/storage/emulated/0/AlbumA/Song.flac", contentUri = "content://tracks/1"),
                scannedFile(filePath = "/storage/emulated/0/AlbumB/Song.flac", contentUri = "content://tracks/2"),
            )
        )

        val resolved = index.resolve("Elsewhere/Song.flac", parentDisplayPath = "")

        assertNull(resolved)
    }

    @Test
    fun `given no candidate matches at all when resolved then null is returned`() {
        val index = M3uFileScanner.TrackPathIndex(
            listOf(scannedFile(filePath = "/storage/emulated/0/Music/Song.flac", contentUri = "content://tracks/1"))
        )

        val resolved = index.resolve("/storage/emulated/0/Podcasts/Episode.mp3", parentDisplayPath = "")

        assertNull(resolved)
    }

    @Test
    fun `given an entry that is already one of the scanned content URIs when resolved then it passes through`() {
        val index = M3uFileScanner.TrackPathIndex(
            listOf(scannedFile(filePath = "/storage/emulated/0/Music/Song.flac", contentUri = "content://tracks/1"))
        )

        val resolved = index.resolve("content://tracks/1", parentDisplayPath = "Music/Playlist")

        assertEquals("content://tracks/1", resolved)
    }

    private fun scannedFile(filePath: String, contentUri: String): ScannedAudioFile = ScannedAudioFile(
        id = 1L,
        title = "Song",
        artistId = 1L,
        artistName = "Artist",
        albumId = 1L,
        albumTitle = "Album",
        durationMs = 180_000L,
        contentUri = contentUri,
        filePath = filePath,
        trackNumber = 1,
        discNumber = 1,
        mimeType = "audio/flac",
        fileSizeBytes = 1_000L,
        dateAdded = 0L,
        year = 0,
        artUri = null,
    )
}
