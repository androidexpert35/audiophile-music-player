package com.androidexpert35.audiophilemusicplayer.data.mapper

import com.androidexpert35.audiophilemusicplayer.data.scanner.ScannedAudioFile
import org.junit.Assert.assertEquals
import org.junit.Test

/** Verifies artist catalogue aggregation from raw multi-value metadata credits. */
class LibraryIndexMapperTest {

    @Test
    fun `given delimited artist credits when indexing then creates one entry per artist`() {
        val files = listOf(
            scannedFile(id = 1L, albumId = 10L, artistName = "Artist One; Artist Two"),
            scannedFile(id = 2L, albumId = 20L, artistName = "artist one | Artist Three"),
        )

        val artists = files.toArtistEntities()

        assertEquals(listOf("Artist One", "Artist Three", "Artist Two"), artists.map { it.name })
        assertEquals(2, artists.single { it.name == "Artist One" }.trackCount)
        assertEquals(2, artists.single { it.name == "Artist One" }.albumCount)
    }

    @Test
    fun `given ampersand in artist name when indexing then keeps one artist entry`() {
        val artists = listOf(
            scannedFile(id = 1L, albumId = 10L, artistName = "Bob Marley & the Wailers")
        ).toArtistEntities()

        assertEquals(listOf("Bob Marley & the Wailers"), artists.map { it.name })
    }

    @Test
    fun `given track metadata when indexing then retains genre year and composer`() {
        val source = scannedFile(id = 1L, albumId = 10L, artistName = "Composer Artist").copy(
            year = 1999,
            genre = "Jazz",
            composer = "Duke Ellington",
        )

        val track = source.toTrackEntity().toDomainTrack()

        assertEquals(1999, track.year)
        assertEquals("Jazz", track.genre)
        assertEquals("Duke Ellington", track.composer)
    }

    private fun scannedFile(
        id: Long,
        albumId: Long,
        artistName: String,
    ): ScannedAudioFile = ScannedAudioFile(
        id = id,
        title = "Track $id",
        artistId = artistName.hashCode().toLong(),
        artistName = artistName,
        albumId = albumId,
        albumTitle = "Album $albumId",
        durationMs = 60_000L,
        contentUri = "content://track/$id",
        filePath = "Music/track$id.flac",
        trackNumber = 1,
        discNumber = 1,
        mimeType = "audio/flac",
        fileSizeBytes = 1_000L,
        dateAdded = 1L,
        year = 2026,
        artUri = null,
    )
}
