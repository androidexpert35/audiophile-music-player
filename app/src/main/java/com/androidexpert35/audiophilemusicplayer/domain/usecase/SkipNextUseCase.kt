package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.repository.PlaybackRepository
import com.tony.coreui.domain.resource.Resource

/**
 * Advances to the next track in the playback queue.
 *
 * @property playbackRepository Repository controlling the playback engine.
 */
class SkipNextUseCase(
    private val playbackRepository: PlaybackRepository
) {
    /**
     * @return [Resource.Success] on success, [Resource.Error] if at queue end and repeat is off.
     */
    suspend operator fun invoke(): Resource<Unit> =
        playbackRepository.skipNext()
}

