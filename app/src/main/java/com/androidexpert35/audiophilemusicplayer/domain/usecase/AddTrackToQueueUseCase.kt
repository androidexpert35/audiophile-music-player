package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track
import com.androidexpert35.audiophilemusicplayer.domain.repository.PlaybackRepository
import com.tony.coreui.domain.resource.Resource

/**
 * Appends a selected track to the active playback queue.
 *
 * @property playbackRepository Playback contract that owns the active queue.
 */
class AddTrackToQueueUseCase(
    private val playbackRepository: PlaybackRepository
) {
    /**
     * @param track Track that should become the final queue item.
     * @return Success after insertion, or an error when the playback session rejects it.
     */
    suspend operator fun invoke(track: Track): Resource<Unit> =
        playbackRepository.addToQueue(track)
}
