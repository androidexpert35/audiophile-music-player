package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library

import androidx.compose.runtime.Immutable
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Album
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track

/**
 * Immutable UI state for the album detail screen.
 *
 * @property album Selected album metadata, or `null` until loaded.
 * @property tracks Ordered track list belonging to the album.
 * @property currentPlayingTrackId Identifier of the track currently loaded in playback
 *   when it belongs to this album; used to highlight the active row.
 * @property isLiked Whether the user has locally toggled the liked / saved state for
 *   this album. Resets when the ViewModel is cleared (no persistence layer yet).
 * @property totalDurationMs Combined duration of all album tracks in milliseconds.
 * @property totalSizeBytes Combined on-disk size of all album tracks in bytes.
 * @property discCount Number of discs represented by the album track list.
 * @property losslessTrackCount Number of album tracks encoded losslessly.
 * @property hiResTrackCount Number of album tracks qualifying as hi-resolution audio.
 * @property maxSampleRateHz Highest sample rate found among the album tracks.
 * @property maxBitDepth Highest bit depth found among the album tracks.
 * @property codecSummary Human-readable list of codecs found across the album.
 * @property artistImageUrl Cached Deezer profile image for the album artist, or `null`
 *   when the artist has not been enriched or has no matching profile image.
 * @property playlists Local playlist summaries available to the track action selector.
 * @property playlistPickerTracks Ordered tracks awaiting a target playlist selection.
 */
@Immutable
data class AlbumOverviewUiModel(
    val album: Album? = null,
    val tracks: List<Track> = emptyList(),
    val currentPlayingTrackId: Long? = null,
    val isLiked: Boolean = false,
    val totalDurationMs: Long = 0L,
    val totalSizeBytes: Long = 0L,
    val discCount: Int = 0,
    val losslessTrackCount: Int = 0,
    val hiResTrackCount: Int = 0,
    val maxSampleRateHz: Int = 0,
    val maxBitDepth: Int = 0,
    val codecSummary: String = "",
    val artistImageUrl: String? = null,
    val playlists: List<PlaylistUiModel> = emptyList(),
    val playlistPickerTracks: List<Track> = emptyList()
)
