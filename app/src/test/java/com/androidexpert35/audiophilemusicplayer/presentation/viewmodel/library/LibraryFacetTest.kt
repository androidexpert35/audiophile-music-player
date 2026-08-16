package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library

import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioFormat
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track
import org.junit.Assert.assertEquals
import org.junit.Test

/** Verifies local metadata grouping used by the three metadata library sections. */
class LibraryFacetTest {

    @Test
    fun `given multi-value genre and composer tags when grouped then normalizes names and avoids duplicate tracks`() {
        val tracks = listOf(
            track(id = 1L, genre = "Jazz; Fusion", composer = "Herbie Hancock | Wayne Shorter"),
            track(id = 2L, genre = "jazz", composer = "Herbie Hancock"),
        )

        val genres = tracks.toGenreFacets()
        val composers = tracks.toComposerFacets()

        assertEquals(listOf("Fusion", "Jazz"), genres.map { it.name })
        assertEquals(2, genres.single { it.name == "Jazz" }.trackCount)
        assertEquals(listOf("Herbie Hancock", "Wayne Shorter"), composers.map { it.name })
        assertEquals(2, composers.single { it.name == "Herbie Hancock" }.trackCount)
    }

    @Test
    fun `given known and unknown years when grouped then omits unknown years and orders descending`() {
        val years = listOf(
            track(id = 1L, year = 1970),
            track(id = 2L, year = 0),
            track(id = 3L, year = 2001),
        ).toYearFacets()

        assertEquals(listOf("2001", "1970"), years.map { it.name })
    }

    private fun track(
        id: Long,
        year: Int = 0,
        genre: String? = null,
        composer: String? = null,
    ) = Track(
        id = id,
        title = "Track $id",
        artistName = "Artist",
        albumTitle = "Album",
        albumId = 1L,
        durationMs = 60_000L,
        uri = "content://track/$id",
        trackNumber = 1,
        discNumber = 1,
        audioFormat = AudioFormat.UNKNOWN,
        fileSizeBytes = 1L,
        dateAdded = id,
        year = year,
        genre = genre,
        composer = composer,
    )
}
