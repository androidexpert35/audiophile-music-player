package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.model.playback.ShuffleMode
import com.androidexpert35.audiophilemusicplayer.domain.repository.PlaybackRepository

/**
 * Sets the shuffle mode for the current playback session.
 *
 * @property playbackRepository Repository controlling the playback engine.
 */
class SetShuffleModeUseCase(
    private val playbackRepository: PlaybackRepository
) {
    /**
     * @param mode The desired [ShuffleMode].
     */
    suspend operator fun invoke(mode: ShuffleMode) {
        playbackRepository.setShuffleMode(mode)
    }
}

