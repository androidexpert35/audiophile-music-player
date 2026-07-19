package com.androidexpert35.audiophilemusicplayer.presentation.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRoutesTest {

    @Test
    fun `given route text with reserved characters when built then path is safely encoded`() {
        val route = AppRoutes.artistDescriptionRoute("AC/DC & Björk 東京")

        assertTrue(route.startsWith("artist_description/"))
        assertTrue(route.contains("AC%2FDC%20%26%20Bj%C3%B6rk%20%E6%9D%B1%E4%BA%AC"))
        assertFalse(route.substringAfter("artist_description/").contains("/"))
    }

    @Test
    fun `given typed identifiers when built then routes match registered patterns`() {
        assertEquals("album_overview/42", AppRoutes.albumOverviewRoute(42L))
        assertEquals(
            "playlist_overview/Favorites%20%2F%202026.m3u",
            AppRoutes.playlistOverviewRoute("Favorites / 2026.m3u")
        )
    }
}
