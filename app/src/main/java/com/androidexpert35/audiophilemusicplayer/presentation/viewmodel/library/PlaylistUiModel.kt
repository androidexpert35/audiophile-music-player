package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library

import androidx.compose.runtime.Immutable
import com.androidexpert35.audiophilemusicplayer.domain.model.library.PlaylistKind

/**
 * Supplies the presentation-ready summary of one local playlist.
 *
 * @property id Stable M3U filename identifier used for selection and updates.
 * @property name User-visible playlist name.
 * @property trackCount Number of entries in the playlist.
 * @property albumArtUris Artwork for at most four most recently added tracks, newest first.
 * @property kind Semantic role used to select standard mosaic or favorites artwork.
 */
@Immutable
data class PlaylistUiModel(
    val id: String,
    val name: String,
    val trackCount: Int,
    val albumArtUris: List<String>,
    val kind: PlaylistKind = PlaylistKind.STANDARD
)
