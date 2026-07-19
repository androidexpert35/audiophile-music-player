package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track
import com.androidexpert35.audiophilemusicplayer.domain.repository.MusicRepository
import com.tony.coreui.domain.resource.Resource

/**
 * Searches the local music library by a free-text query.
 *
 * @property musicRepository Repository for accessing local music files.
 */
class SearchTracksUseCase(
    private val musicRepository: MusicRepository
) {
    /**
     * @param query Case-insensitive search string matched against title, artist, and album.
     * @return [Resource.Success] with matching tracks, or [Resource.Error] on failure.
     */
    suspend operator fun invoke(query: String): Resource<List<Track>> =
        musicRepository.searchTracks(query)
}

