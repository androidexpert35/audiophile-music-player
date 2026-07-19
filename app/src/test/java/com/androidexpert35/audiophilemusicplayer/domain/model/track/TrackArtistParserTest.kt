package com.androidexpert35.audiophilemusicplayer.domain.model.track

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for artist-credit parsing used by player navigation affordances.
 */
class TrackArtistParserTest {

    @Test
    fun `given featuring credit when extracting navigable artists then returns primary and featured artists`() {
        val result = extractNavigableArtistNames("The Weeknd feat. Daft Punk")

        assertEquals(listOf("The Weeknd", "Daft Punk"), result)
    }

    @Test
    fun `given collaboration delimiters when extracting navigable artists then splits all collaborators`() {
        val result = extractNavigableArtistNames("Skrillex, Fred again.. & Flowdan")

        assertEquals(listOf("Skrillex", "Fred again..", "Flowdan"), result)
    }

    @Test
    fun `given duplicated artist names when extracting navigable artists then keeps only first unique entry`() {
        val result = extractNavigableArtistNames("Rosalia feat. ROSALIA")

        assertEquals(listOf("Rosalia"), result)
    }

    @Test
    fun `given blank artist credit when extracting navigable artists then returns empty list`() {
        val result = extractNavigableArtistNames("   ")

        assertTrue(result.isEmpty())
    }

    @Test
    fun `given selected featured artist when matching navigable artist then returns true`() {
        val result = containsNavigableArtist(
            artistDisplayName = "Lady Gaga feat. Beyonce",
            artistName = "beyonce"
        )

        assertTrue(result)
    }

    @Test
    fun `given absent artist when matching navigable artist then returns false`() {
        val result = containsNavigableArtist(
            artistDisplayName = "Massive Attack",
            artistName = "Portishead"
        )

        assertFalse(result)
    }
}
