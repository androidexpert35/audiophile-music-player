package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.repository.PlaybackRepository
import com.tony.coreui.domain.resource.Resource

/**
 * Seeks to a specific position within the currently playing track.
 *
 * @property playbackRepository Repository controlling the playback engine.
 */
class SeekToPositionUseCase(
    private val playbackRepository: PlaybackRepository
) {
    /**
     * @param positionMs Target position in milliseconds.
     * @return [Resource.Success] on success, [Resource.Error] if out of range.
     */
    suspend operator fun invoke(positionMs: Long): Resource<Unit> =
        playbackRepository.seekTo(positionMs)
}

