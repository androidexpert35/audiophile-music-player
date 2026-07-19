package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.player

import androidx.compose.runtime.Immutable
import com.androidexpert35.audiophilemusicplayer.domain.model.playback.PlaybackState
import com.androidexpert35.audiophilemusicplayer.domain.model.playback.QueueState

/**
 * Immutable UI state for the now-playing / player screen.
 *
 * Combines the four core reactive streams (playback state, audio telemetry,
 * queue state, and liked-song IDs) into a single snapshot that the UI layer observes.
 *
 * @property playbackState Current playback snapshot (status, track, position).
 * @property queueState Current queue snapshot (tracks, index, repeat, shuffle).
 * @property likedSongIds Set of track IDs the user has marked as liked, used to
 *   drive the heart icon state in the now-playing panel.
 */
@Immutable
data class PlayerUiModel(
    val playbackState: PlaybackState = PlaybackState.IDLE,
    val queueState: QueueState = QueueState.EMPTY,
    val likedSongIds: Set<Long> = emptySet()
)

