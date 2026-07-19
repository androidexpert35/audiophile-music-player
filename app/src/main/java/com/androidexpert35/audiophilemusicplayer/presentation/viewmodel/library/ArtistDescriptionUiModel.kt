package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library

import androidx.compose.runtime.Immutable
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Album
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track

/**
 * Immutable UI state for the artist description screen.
 *
 * Presents a Spotify-style artist profile with a hero image, popular tracks,
 * primary discography, and featured-appearance albums. All list data originates
 * from the local MediaStore index, enriched with a remote artist image when
 * available via the Deezer integration.
 *
 * @property artistName Display name of the artist whose profile is shown.
 * @property artistImageUrl HTTPS URL for the Deezer artist photo, or `null` when
 *   the remote lookup has not yet completed or returned no match.
 * @property popularTracks Personally most-played tracks in descending play-count
 *   order, used as the "Popular Songs" horizontal row. Empty until the user has
 *   listened to at least one track by this artist.
 * @property allTracks Full ordered track list for this artist, used as the playback
 *   queue when playing the artist or a specific track.
 * @property albums Albums whose primary artist credit matches this artist.
 * @property appearsOnAlbums Albums where the artist appears as a featured credit
 *   but is not the primary album artist.
 * @property currentPlayingTrackId Identifier of the track currently loaded in the
 *   playback engine when it belongs to this artist, used to highlight the active row.
 * @property isFollowed Whether the user has locally toggled the follow state for
 *   this artist. Resets when the ViewModel is cleared (no persistence layer yet).
 * @property albumCount Number of distinct primary albums attributed to this artist.
 * @property totalDurationMs Sum of all visible track durations in milliseconds.
 * @property losslessTrackCount Number of lossless-encoded tracks in the artist's library.
 * @property hiResTrackCount Number of hi-res tracks (≥ 88.2 kHz or ≥ 24-bit).
 * @property maxSampleRateHz Highest encoded sample rate present in the artist's tracks.
 * @property maxBitDepth Highest encoded bit depth present in the artist's tracks.
 * @property codecSummary Human-readable codec summary (e.g. "FLAC • AAC") for the hero card.
 */
@Immutable
data class ArtistDescriptionUiModel(
    val artistName: String = "",
    val artistImageUrl: String? = null,
    val popularTracks: List<Track> = emptyList(),
    val allTracks: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
    val appearsOnAlbums: List<Album> = emptyList(),
    val currentPlayingTrackId: Long? = null,
    val isFollowed: Boolean = false,
    val albumCount: Int = 0,
    val totalDurationMs: Long = 0L,
    val losslessTrackCount: Int = 0,
    val hiResTrackCount: Int = 0,
    val maxSampleRateHz: Int = 0,
    val maxBitDepth: Int = 0,
    val codecSummary: String = ""
)
