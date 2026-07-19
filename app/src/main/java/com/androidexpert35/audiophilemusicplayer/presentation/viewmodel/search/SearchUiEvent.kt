package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.search

import com.androidexpert35.audiophilemusicplayer.domain.model.track.Album
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Artist
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track

/**
 * User intents emitted from the search screen.
 */
sealed interface SearchUiEvent {

    /**
     * The user changed the text in the search field.
     *
     * @property query Updated search string; may be blank to indicate a clear.
     */
    data class QueryChanged(val query: String) : SearchUiEvent

    /** The user explicitly cleared the active search. */
    data object ClearSearch : SearchUiEvent

    /**
     * Lazily enriches a visible artist search result with its image.
     *
     * @property artist Visible artist whose cached image should be resolved.
     */
    data class LoadArtistImage(val artist: Artist) : SearchUiEvent

    /**
     * The user tapped a track result to begin playback.
     *
     * @property track Track that should start playing immediately.
     */
    data class PlayTrack(val track: Track) : SearchUiEvent

    /**
     * The user tapped an album result to open its detail overview.
     *
     * @property album Album whose detail screen should be opened.
     */
    data class OpenAlbumOverview(val album: Album) : SearchUiEvent

    /**
     * The user tapped an artist result to open the Spotify-style artist profile.
     *
     * @property artist Artist whose profile screen should be opened.
     */
    data class OpenArtistDescription(val artist: Artist) : SearchUiEvent
}
