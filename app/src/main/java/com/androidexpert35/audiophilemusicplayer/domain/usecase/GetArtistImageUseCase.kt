package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.repository.RemoteImageRepository
import com.tony.coreui.domain.resource.Resource

/**
 * Retrieves a remote artist image URL from the Deezer API, with automatic
 * Room caching to avoid redundant network calls on subsequent invocations.
 *
 * On the first call for a given artist the repository performs a live network
 * request, caches the resolved URL in the local database, and returns it.
 * All later calls for the same artist name are served directly from Room.
 *
 * @property remoteImageRepository Repository that coordinates Deezer search
 *   and Room caching for remote image URLs.
 * @constructor Creates the use case with its required repository dependency.
 * @see RemoteImageRepository.getArtistImageUrl
 */
class GetArtistImageUseCase(
    private val remoteImageRepository: RemoteImageRepository
) {

    /**
     * Fetches or returns a cached Deezer image URL for [artistName].
     *
     * @param artistName Display name of the artist to look up.
     * @return [Resource.Success] containing the HTTPS image URL,
     *         [Resource.Error] when no result is found or the network is unavailable.
     */
    suspend operator fun invoke(artistName: String): Resource<String> =
        remoteImageRepository.getArtistImageUrl(artistName)
}

