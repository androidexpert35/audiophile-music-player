package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.repository.PlaybackRepository
import com.tony.coreui.domain.resource.Resource

/**
 * Resumes playback from the paused position.
 *
 * @property playbackRepository Repository controlling the playback engine.
 */
class ResumePlaybackUseCase(
    private val playbackRepository: PlaybackRepository
) {
    /**
     * @return [Resource.Success] on success, [Resource.Error] if no media is loaded.
     */
    suspend operator fun invoke(): Resource<Unit> =
        playbackRepository.resume()
}

