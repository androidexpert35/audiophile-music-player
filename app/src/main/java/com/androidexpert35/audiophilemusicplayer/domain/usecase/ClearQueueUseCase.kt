package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.repository.PlaybackRepository
import com.tony.coreui.domain.resource.Resource

/**
 * Removes every queued track while allowing the current track to continue playing.
 *
 * @property playbackRepository Playback command boundary that owns the active Media3 queue.
 */
class ClearQueueUseCase(
    private val playbackRepository: PlaybackRepository
) {

    /** @return The result of retaining only the active queue item and its saved session. */
    suspend operator fun invoke(): Resource<Unit> = playbackRepository.clearQueue()
}
