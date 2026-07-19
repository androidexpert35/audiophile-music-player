package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.repository.RemoteImageRepository
import com.tony.coreui.domain.resource.Resource

/**
 * Retrieves a remote album cover URL from the Deezer API, with automatic Room
 * caching to avoid redundant network calls on subsequent invocations.
 *
 * On the first call for a given album/artist combination the repository performs
 * a live Deezer search, caches the resolved URL in the local database, and returns
 * it. All later calls for the same pair are served directly from Room.
 *
 * This use case is intended as a fallback enrichment layer: it should be invoked
 * only when the local MediaStore artwork is absent or fails to load.
 *
 * @property remoteImageRepository Repository that coordinates Deezer search
 *   and Room caching for remote cover URLs.
 * @constructor Creates the use case with its required repository dependency.
 * @see RemoteImageRepository.getAlbumArtUrl
 */
class GetAlbumArtUseCase(
    private val remoteImageRepository: RemoteImageRepository
) {

    /**
     * Fetches or returns a cached Deezer cover URL for the given album.
     *
     * @param albumTitle Title of the album to look up.
     * @param artistName Name of the album artist, used to narrow the Deezer query.
     * @return [Resource.Success] containing the HTTPS cover URL,
     *         [Resource.Error] when no result is found or the network is unavailable.
     */
    suspend operator fun invoke(albumTitle: String, artistName: String): Resource<String> =
        remoteImageRepository.getAlbumArtUrl(albumTitle, artistName)
}

