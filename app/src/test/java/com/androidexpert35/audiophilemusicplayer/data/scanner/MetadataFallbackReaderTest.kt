package com.androidexpert35.audiophilemusicplayer.data.scanner

import android.content.Context
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataFallbackReaderTest {

    private val reader = MetadataFallbackReader(mockk<Context>())

    @Test
    fun `given media store omits year for fully named FLAC when checked then fallback is requested`() {
        val file = sampleFile(year = 0)

        assertTrue(reader.needsFallback(file))
    }

    @Test
    fun `given media store supplies year and metadata when checked then fallback is not requested`() {
        val file = sampleFile(year = 2024)

        assertFalse(reader.needsFallback(file))
    }

    private fun sampleFile(year: Int) = ScannedAudioFile(
        id = 1L,
        title = "Track",
        artistId = 2L,
        artistName = "Artist",
        albumId = 3L,
        albumTitle = "Album",
        durationMs = 180_000L,
        contentUri = "content://media/external/audio/media/1",
        filePath = "Music/Album/Track.flac",
        trackNumber = 1,
        discNumber = 1,
        mimeType = "audio/flac",
        fileSizeBytes = 1L,
        dateAdded = 0L,
        year = year,
        artUri = null,
    )
}
