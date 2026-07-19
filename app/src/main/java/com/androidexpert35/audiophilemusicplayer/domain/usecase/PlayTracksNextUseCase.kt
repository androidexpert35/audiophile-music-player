package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track
import com.androidexpert35.audiophilemusicplayer.domain.repository.PlaybackRepository
import com.tony.coreui.domain.resource.Resource

/**
 * Schedules an ordered track collection immediately after the active item.
 *
 * @property playbackRepository Playback contract that owns atomic queue mutations.
 */
class PlayTracksNextUseCase(
    private val playbackRepository: PlaybackRepository
) {
    /**
     * @param tracks Ordered tracks that should play after the current item.
     * @return Success after inserting the complete collection, or a playback error.
     */
    suspend operator fun invoke(tracks: List<Track>): Resource<Unit> =
        playbackRepository.playNext(tracks)
}
