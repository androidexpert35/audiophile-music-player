package com.androidexpert35.audiophilemusicplayer.presentation.screen.common.components

import com.androidexpert35.audiophilemusicplayer.domain.model.library.PlaylistKind
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library.PlaylistUiModel
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistPickerDialogTest {

    @Test
    fun `given favorites and standard playlists when destinations resolved then favorites are excluded`() {
        val favorites = playlist(id = "favorites.m3u", kind = PlaylistKind.FAVORITES)
        val standard = playlist(id = "road-trip.m3u", kind = PlaylistKind.STANDARD)

        val destinations = listOf(favorites, standard).selectablePlaylistDestinations()

        assertEquals(listOf(standard), destinations)
    }

    private fun playlist(id: String, kind: PlaylistKind): PlaylistUiModel = PlaylistUiModel(
        id = id,
        name = id,
        trackCount = 0,
        albumArtUris = emptyList(),
        kind = kind
    )
}
