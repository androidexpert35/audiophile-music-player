package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.model.playback.RepeatMode
import com.androidexpert35.audiophilemusicplayer.domain.repository.PlaybackRepository

/**
 * Sets the repeat mode for the current playback session.
 *
 * @property playbackRepository Repository controlling the playback engine.
 */
class SetRepeatModeUseCase(
    private val playbackRepository: PlaybackRepository
) {
    /**
     * @param mode The desired [RepeatMode].
     */
    suspend operator fun invoke(mode: RepeatMode) {
        playbackRepository.setRepeatMode(mode)
    }
}

