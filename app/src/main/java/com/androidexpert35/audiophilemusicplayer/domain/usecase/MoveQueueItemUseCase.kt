package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.repository.PlaybackRepository
import com.tony.coreui.domain.resource.Resource

/**
 * Repositions one track inside the active playback queue.
 *
 * @property playbackRepository Playback contract that owns the active queue.
 */
class MoveQueueItemUseCase(
    private val playbackRepository: PlaybackRepository
) {
    /**
     * @param fromIndex Current zero-based queue position.
     * @param toIndex Target zero-based queue position.
     * @return Success after the move, or an error when the positions are invalid.
     */
    suspend operator fun invoke(fromIndex: Int, toIndex: Int): Resource<Unit> =
        playbackRepository.moveQueueItem(fromIndex, toIndex)
}
