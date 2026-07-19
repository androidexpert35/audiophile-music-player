package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.model.track.Artist
import com.androidexpert35.audiophilemusicplayer.domain.repository.MusicRepository
import com.tony.coreui.domain.resource.Resource

/**
 * Retrieves the complete list of artists from the device library.
 *
 * @property musicRepository Repository for accessing local music files.
 */
class GetArtistsUseCase(
    private val musicRepository: MusicRepository
) {
    /**
     * @return [Resource.Success] with all artists, or [Resource.Error] on failure.
     */
    suspend operator fun invoke(): Resource<List<Artist>> =
        musicRepository.getArtists()
}

