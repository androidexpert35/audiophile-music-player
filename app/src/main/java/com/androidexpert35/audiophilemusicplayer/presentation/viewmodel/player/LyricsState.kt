package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.player

import com.androidexpert35.audiophilemusicplayer.domain.model.lyrics.Lyrics

/**
 * Represents all possible states of the lyrics panel in the player screen.
 *
 * Lyrics are fetched lazily — only after the user explicitly requests them —
 * so the initial state is always [Idle]. The ViewModel resets to [Idle] on
 * every track change so stale lyrics never bleed across track boundaries.
 */
sealed interface LyricsState {

    /** No fetch has been initiated for the current track. */
    data object Idle : LyricsState

    /** A network request is in flight. */
    data object Loading : LyricsState

    /**
     * Lyrics were successfully retrieved.
     *
     * @property lyrics The resolved [Lyrics] payload.
     *   [Lyrics.lines] may be empty when only plain text is available.
     */
    data class Success(val lyrics: Lyrics) : LyricsState

    /**
     * The lyrics service returned no match for this track.
     *
     * Distinct from [Error] because "not found" is an expected outcome
     * that the UI should present differently from a network failure.
     */
    data object NotFound : LyricsState

    /** The track's audio content contains no vocals. */
    data object Instrumental : LyricsState

    /**
     * A network or storage failure prevented lyrics from loading.
     *
     * @property message Human-readable description of the failure, safe for display.
     */
    data class Error(val message: String) : LyricsState
}

