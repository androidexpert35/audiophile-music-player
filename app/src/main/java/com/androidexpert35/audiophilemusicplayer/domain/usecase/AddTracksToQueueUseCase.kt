package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track
import com.androidexpert35.audiophilemusicplayer.domain.repository.PlaybackRepository
import com.tony.coreui.domain.resource.Resource

/**
 * Appends an ordered track collection to the active playback queue.
 *
 * @property playbackRepository Playback contract that owns atomic queue mutations.
 */
class AddTracksToQueueUseCase(
    private val playbackRepository: PlaybackRepository
) {
    /**
     * @param tracks Ordered tracks that should become the final queue items.
     * @return Success after appending the complete collection, or a playback error.
     */
    suspend operator fun invoke(tracks: List<Track>): Resource<Unit> =
        playbackRepository.addToQueue(tracks)
}
