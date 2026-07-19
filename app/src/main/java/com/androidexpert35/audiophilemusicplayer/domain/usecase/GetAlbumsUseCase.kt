package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.model.track.Album
import com.androidexpert35.audiophilemusicplayer.domain.repository.MusicRepository
import com.tony.coreui.domain.resource.Resource

/**
 * Retrieves the complete list of albums from the device library.
 *
 * @property musicRepository Repository for accessing local music files.
 */
class GetAlbumsUseCase(
    private val musicRepository: MusicRepository
) {
    /**
     * @return [Resource.Success] with all albums, or [Resource.Error] on failure.
     */
    suspend operator fun invoke(): Resource<List<Album>> =
        musicRepository.getAlbums()
}

