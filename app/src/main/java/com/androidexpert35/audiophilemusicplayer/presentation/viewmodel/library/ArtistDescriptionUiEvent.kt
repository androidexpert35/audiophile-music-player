package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library

import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track

/**
 * User intents emitted from the artist description screen.
 *
 * All events flow up from the Composable to [ArtistDescriptionViewModel.onEvent],
 * enforcing unidirectional data flow.
 */
sealed interface ArtistDescriptionUiEvent {

    /**
     * Triggers the initial data load for the given artist name.
     *
     * Idempotent — if the same artist is already loaded, the ViewModel skips
     * a redundant fetch and returns immediately.
     *
     * @property artistName Artist name passed from the navigation back-stack entry.
     */
    data class Initialize(val artistName: String) : ArtistDescriptionUiEvent

    /**
     * Starts sequential playback from the first track in the artist's full queue,
     * preserving the current track ordering (disc → track number → title).
     */
    data object PlayArtist : ArtistDescriptionUiEvent

    /**
     * Starts shuffle playback by randomising the full artist queue and beginning
     * from the first track of the shuffled ordering.
     */
    data object ShuffleArtist : ArtistDescriptionUiEvent

    /**
     * Starts playback from the selected track within the full artist queue.
     *
     * @property track Track selected from the popular-songs row or any other
     *   surface on this screen.
     */
    data class PlayTrack(val track: Track) : ArtistDescriptionUiEvent

    /**
     * Toggles the local in-memory follow state for this artist.
     *
     * The state resets when the ViewModel is cleared (no persistence layer yet).
     */
    data object ToggleFollow : ArtistDescriptionUiEvent

    /**
     * Navigates to the album overview screen for the selected album.
     *
     * @property albumId MediaStore album identifier for the destination.
     */
    data class OpenAlbum(val albumId: Long) : ArtistDescriptionUiEvent

    /** Returns to the previous destination via the navigation back stack. */
    data object NavigateBack : ArtistDescriptionUiEvent
}

