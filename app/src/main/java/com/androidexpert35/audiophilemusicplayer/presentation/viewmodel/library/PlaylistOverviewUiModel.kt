package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library

import androidx.compose.runtime.Immutable
import com.androidexpert35.audiophilemusicplayer.domain.model.library.PlaylistKind
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track

/**
 * Holds the listener-facing state of one local playlist page.
 *
 * @property playlistId Stable M3U filename identifier.
 * @property playlistName User-visible playlist title.
 * @property playlistKind Semantic role used by the shared playlist hero artwork.
 * @property albumArtUris Artwork for up to four recently added playable tracks.
 * @property tracks Playable tracks in their persisted playlist order.
 * @property trackCount Number of entries in the playlist, including unavailable entries.
 * @property unavailableTrackCount Entries no longer represented in the local media index.
 * @property searchQuery Local filter applied only to this playlist's tracks.
 * @property isSearchActive Whether the page is currently dedicated to playlist search results.
 * @property isEditing Whether manual track ordering is enabled.
 * @property currentPlayingTrackId Identifier of the currently playing playlist track, if any.
 * @property isDeletePlaylistDialogVisible Whether the delete-confirmation dialog is open.
 */
@Immutable
data class PlaylistOverviewUiModel(
    val playlistId: String = "",
    val playlistName: String = "",
    val playlistKind: PlaylistKind = PlaylistKind.STANDARD,
    val albumArtUris: List<String> = emptyList(),
    val tracks: List<Track> = emptyList(),
    val trackCount: Int = 0,
    val unavailableTrackCount: Int = 0,
    val searchQuery: String = "",
    val isSearchActive: Boolean = false,
    val isEditing: Boolean = false,
    val currentPlayingTrackId: Long? = null,
    val isDeletePlaylistDialogVisible: Boolean = false
) {
    /** Tracks matching [searchQuery] by title, artist, or album; the full list when blank. */
    val visibleTracks: List<Track>
        get() {
            val query = searchQuery.trim()
            return if (query.isEmpty()) {
                tracks
            } else {
                tracks.filter { track ->
                    track.title.contains(query, ignoreCase = true) ||
                        track.artistName.contains(query, ignoreCase = true) ||
                        track.albumTitle.contains(query, ignoreCase = true)
                }
            }
        }
}
