package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.repository.PlaybackRepository
import com.tony.coreui.domain.resource.Resource

/**
 * Returns to the previous track in the playback queue.
 *
 * @property playbackRepository Repository controlling the playback engine.
 */
class SkipPreviousUseCase(
    private val playbackRepository: PlaybackRepository
) {
    /**
     * @return [Resource.Success] on success, [Resource.Error] if at queue start.
     */
    suspend operator fun invoke(): Resource<Unit> =
        playbackRepository.skipPrevious()
}

