package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.repository.PlaybackRepository
import com.tony.coreui.domain.resource.Resource

/**
 * Clears the active playback queue so it cannot be resumed accidentally.
 *
 * @property playbackRepository Playback command boundary that owns the active Media3 queue.
 */
class ClearQueueUseCase(
    private val playbackRepository: PlaybackRepository
) {

    /** @return The result of clearing the active queue and its saved session. */
    suspend operator fun invoke(): Resource<Unit> = playbackRepository.clearQueue()
}
