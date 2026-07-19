package com.androidexpert35.audiophilemusicplayer.data.remote

import com.androidexpert35.audiophilemusicplayer.data.remote.dto.DeezerArtistDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeezerArtistMatchResolverTest {

    @Test
    fun `given similar artist precedes exact artist when resolving then exact artist wins`() {
        val similar = artist(
            id = 1L,
            name = "Michael Jackson Tribute",
            fanCount = 2_000_000L
        )
        val exact = artist(
            id = 2L,
            name = "Michael Jackson",
            fanCount = 1_000_000L
        )

        val result = resolveDeezerArtistMatch(
            artistName = "Michael Jackson",
            candidates = listOf(similar, exact)
        )

        assertEquals(exact, result)
    }

    @Test
    fun `given multiple exact names when resolving then most followed profile wins`() {
        val obscure = artist(id = 1L, name = "Phoenix", fanCount = 100L)
        val canonical = artist(id = 2L, name = "Phoenix", fanCount = 900_000L)

        val result = resolveDeezerArtistMatch(
            artistName = "Phoenix",
            candidates = listOf(obscure, canonical)
        )

        assertEquals(canonical, result)
    }

    @Test
    fun `given punctuation and diacritics differ when resolving then equivalent name matches`() {
        val candidate = artist(id = 1L, name = "Beyonce", fanCount = 1L)

        val result = resolveDeezerArtistMatch(
            artistName = "Beyoncé",
            candidates = listOf(candidate)
        )

        assertEquals(candidate, result)
    }

    @Test
    fun `given only partial matches when resolving then no image is accepted`() {
        val result = resolveDeezerArtistMatch(
            artistName = "Jackson",
            candidates = listOf(
                artist(id = 1L, name = "Michael Jackson", fanCount = 1_000L),
                artist(id = 2L, name = "The Jackson 5", fanCount = 900L)
            )
        )

        assertNull(result)
    }

    private fun artist(
        id: Long,
        name: String,
        fanCount: Long
    ): DeezerArtistDto = DeezerArtistDto(
        id = id,
        name = name,
        pictureXl = "https://cdn.example/$id.jpg",
        fanCount = fanCount
    )
}
