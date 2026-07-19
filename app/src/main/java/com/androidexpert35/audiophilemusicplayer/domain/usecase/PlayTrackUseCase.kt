package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track
import com.androidexpert35.audiophilemusicplayer.domain.repository.PlaybackRepository
import com.tony.coreui.domain.resource.Resource

/**
 * Starts playback of a specific track within a queue context.
 *
 * @property playbackRepository Repository controlling the playback engine.
 */
class PlayTrackUseCase(
    private val playbackRepository: PlaybackRepository
) {
    /**
     * @param track The track to begin playing.
     * @param queue Ordered list of tracks forming the playback queue.
     * @return [Resource.Success] on successful start, [Resource.Error] on failure.
     */
    suspend operator fun invoke(track: Track, queue: List<Track>): Resource<Unit> =
        playbackRepository.play(track, queue)
}

