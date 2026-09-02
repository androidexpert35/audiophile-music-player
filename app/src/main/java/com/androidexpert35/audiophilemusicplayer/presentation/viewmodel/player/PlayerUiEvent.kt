package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.player

import com.androidexpert35.audiophilemusicplayer.domain.model.playback.RepeatMode
import com.androidexpert35.audiophilemusicplayer.domain.model.playback.ShuffleMode
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track

/**
 * User intents emitted from the player screen (or any playback control surface).
 */
sealed interface PlayerUiEvent {

    /**
     * Start playback of a specific track within a queue context.
     *
     * @property track The track to begin playing.
     * @property queue Ordered list of tracks forming the playback queue.
     */
    data class Play(val track: Track, val queue: List<Track>) : PlayerUiEvent

    /** Pause the currently playing track. */
    data object Pause : PlayerUiEvent

    /** Pauses playback and explicitly returns the USB DAC to Android. */
    data object ReleaseUsbAudio : PlayerUiEvent

    /** Releases USB audio before closing the application task. */
    data object ExitAndReleaseUsbAudio : PlayerUiEvent

    /** Resume playback from the paused position. */
    data object Resume : PlayerUiEvent

    /** Advance to the next track in the queue. */
    data object SkipNext : PlayerUiEvent

    /** Return to the previous track in the queue. */
    data object SkipPrevious : PlayerUiEvent

    /**
     * Seek to a specific position within the current track.
     *
     * @property positionMs Target position in milliseconds.
     */
    data class SeekTo(val positionMs: Long) : PlayerUiEvent

    /**
     * Set the repeat mode.
     *
     * @property mode The desired [RepeatMode].
     */
    data class SetRepeatMode(val mode: RepeatMode) : PlayerUiEvent

    /**
     * Set the shuffle mode.
     *
     * @property mode The desired [ShuffleMode].
     */
    data class SetShuffleMode(val mode: ShuffleMode) : PlayerUiEvent

    /**
     * Moves one track within the active playback queue.
     *
     * @property fromIndex Current zero-based queue position.
     * @property toIndex Target zero-based queue position.
     */
    data class MoveQueueItem(val fromIndex: Int, val toIndex: Int) : PlayerUiEvent

    /** Clears every track from the active playback queue. */
    data object ClearQueue : PlayerUiEvent

    /**
     * Opens the album overview destination for the currently playing track.
     *
     * @property albumId Stable album identifier used for album navigation.
     */
    data class NavigateToAlbum(val albumId: Long) : PlayerUiEvent

    /**
     * Opens the artist overview destination for a selected artist credit.
     *
     * @property artistName Exact artist name chosen by the user.
     */
    data class NavigateToArtist(val artistName: String) : PlayerUiEvent

    /**
     * Toggles the liked state of the currently playing track.
     *
     * @property trackId Stable MediaStore identifier of the track to like or unlike.
     */
    data class ToggleLikeSong(val trackId: Long) : PlayerUiEvent

    /**
     * Requests lyrics for the currently playing track.
     *
     * The ViewModel fetches from the network (or serves from cache) only when this
     * event is received — lyrics are never pre-fetched on track load.
     */
    data object RequestLyrics : PlayerUiEvent
}
