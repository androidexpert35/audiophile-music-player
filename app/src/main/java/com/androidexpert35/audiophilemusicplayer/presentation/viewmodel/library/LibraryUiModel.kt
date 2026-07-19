package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library

import androidx.compose.runtime.Immutable
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Album
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Artist
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track

/**
 * Immutable UI state for the music library browser screen.
 *
 * The mini-player is handled globally by the shared app shell — this model
 * only contains library-specific data. Track search belongs to the dedicated
 * Search destination and is intentionally absent from this model.
 *
 * @property tracks All audio tracks on the device.
 * @property playlists Locally stored M3U playlists rendered in the Playlists section.
 * @property albums All albums on the device.
 * @property artists All artists on the device.
 * @property likedSongIds Set of track IDs the user has liked; drives heart-icon state on each row.
 * @property recentlyPlayedTrackIds Ordered list of track IDs played most-recently first;
 *   used by [LibrarySortOrder.RECENTLY_PLAYED] to sort the visible list.
 * @property selectedContentType Section currently visible in the catalogue surface.
 *   Defaults to the first chip, [LibraryContentType.TRACKS], when the library opens.
 * @property isGridView Whether the content should be rendered as a two-column grid.
 *   Defaults to `false` (list view).
 * @property sortOrder Active sort strategy applied to all catalogue sections.
 *   Defaults to [LibrarySortOrder.RECENTLY_ADDED].
 * @property isRefreshing `true` while a user-requested or MediaStore-change re-index is in
 *   progress; drives the spinning animation on the header icon.
 * @property isCreatePlaylistDialogVisible Whether the create-playlist dialog is open.
 * @property playlistPickerTrack Track awaiting a target playlist selection, if any.
 */
@Immutable
data class LibraryUiModel(
    val tracks: List<Track> = emptyList(),
    val playlists: List<PlaylistUiModel> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val likedSongIds: Set<Long> = emptySet(),
    val recentlyPlayedTrackIds: List<Long> = emptyList(),
    val selectedContentType: LibraryContentType = LibraryContentType.TRACKS,
    val isGridView: Boolean = false,
    val sortOrder: LibrarySortOrder = LibrarySortOrder.RECENTLY_ADDED,
    val isRefreshing: Boolean = false,
    val isCreatePlaylistDialogVisible: Boolean = false,
    val playlistPickerTrack: Track? = null
) {
    /**
     * Derived list of tracks the user has liked, preserving the current catalogue order.
     *
     * Computed as a derived property so the View never has to filter in Composable scope.
     */
    val likedTracks: List<Track>
        get() = tracks.filter { it.id in likedSongIds }

    /**
     * Derived ordered list of recently-played [Track] objects, most-recent first.
     *
     * Tracks that appear in [recentlyPlayedTrackIds] but are no longer in [tracks]
     * (e.g. deleted from device) are silently skipped.
     */
    val recentlyPlayedTracks: List<Track>
        get() {
            val trackMap = tracks.associateBy { it.id }
            return recentlyPlayedTrackIds.mapNotNull { id -> trackMap[id] }
        }
}
