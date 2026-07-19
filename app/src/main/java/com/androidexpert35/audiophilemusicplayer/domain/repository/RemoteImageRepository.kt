package com.androidexpert35.audiophilemusicplayer.domain.repository

import com.tony.coreui.domain.resource.Resource

/**
 * Contract for fetching remote artist and album artwork URLs via the Deezer API.
 *
 * Implementations are responsible for:
 * - Querying the Deezer search endpoints.
 * - Caching successfully resolved URLs in the local Room database so that
 *   subsequent calls bypass the network entirely.
 * - Returning [Resource.Error] with a [com.tony.coreui.domain.resource.ResourceError.NetworkError]
 *   on connectivity failures or when no matching result is found.
 */
interface RemoteImageRepository {

    /**
     * Resolves a remote artist image URL for the given [artistName].
     *
     * Returns the cached URL when one exists in Room, otherwise performs a
     * Deezer artist search and caches the result before returning.
     *
     * @param artistName Display name of the artist to look up.
     * @return [Resource.Success] containing the HTTPS image URL on success,
     *         [Resource.Error] when the network call fails or Deezer returns no result.
     */
    suspend fun getArtistImageUrl(artistName: String): Resource<String>

    /**
     * Resolves a remote album cover URL for the given [albumTitle] and [artistName].
     *
     * Returns the cached URL when one exists in Room, otherwise performs a
     * Deezer album search and caches the result before returning.
     *
     * @param albumTitle Title of the album to look up.
     * @param artistName Name of the album artist, used to narrow the search query.
     * @return [Resource.Success] containing the HTTPS cover URL on success,
     *         [Resource.Error] when the network call fails or Deezer returns no result.
     */
    suspend fun getAlbumArtUrl(albumTitle: String, artistName: String): Resource<String>
}

