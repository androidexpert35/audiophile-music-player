package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.model.playback.PlaybackState
import com.androidexpert35.audiophilemusicplayer.domain.repository.PlaybackRepository
import kotlinx.coroutines.flow.Flow

/**
 * Observes the current playback state as a continuous reactive stream.
 *
 * @property playbackRepository Repository controlling the playback engine.
 */
class ObservePlaybackStateUseCase(
    private val playbackRepository: PlaybackRepository
) {
    /**
     * @return A [Flow] emitting [PlaybackState] snapshots on every state change.
     */
    operator fun invoke(): Flow<PlaybackState> =
        playbackRepository.observePlaybackState()
}

