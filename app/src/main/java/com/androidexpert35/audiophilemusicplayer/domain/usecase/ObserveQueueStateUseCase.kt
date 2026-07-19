package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.model.playback.QueueState
import com.androidexpert35.audiophilemusicplayer.domain.repository.PlaybackRepository
import kotlinx.coroutines.flow.Flow

/**
 * Observes the current playback queue state as a continuous reactive stream.
 *
 * @property playbackRepository Repository controlling the playback engine.
 */
class ObserveQueueStateUseCase(
    private val playbackRepository: PlaybackRepository
) {
    /**
     * @return A [Flow] emitting [QueueState] snapshots on every queue change.
     */
    operator fun invoke(): Flow<QueueState> =
        playbackRepository.observeQueueState()
}

