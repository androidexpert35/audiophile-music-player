package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track
import com.androidexpert35.audiophilemusicplayer.domain.repository.MusicRepository
import com.tony.coreui.domain.resource.Resource

/**
 * Retrieves the complete list of audio tracks from the device library.
 *
 * @property musicRepository Repository for accessing local music files.
 */
class GetTracksUseCase(
    private val musicRepository: MusicRepository
) {
    /**
     * @return [Resource.Success] with all tracks, or [Resource.Error] on failure.
     */
    suspend operator fun invoke(): Resource<List<Track>> =
        musicRepository.getTracks()
}

