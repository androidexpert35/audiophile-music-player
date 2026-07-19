package com.androidexpert35.audiophilemusicplayer.domain.repository

import com.androidexpert35.audiophilemusicplayer.domain.model.track.Album
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Artist
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track
import com.tony.coreui.domain.resource.Resource

/**
 * Abstraction over the app's local music catalogue.
 *
 * Implementations are free to build the catalogue from MediaStore, Room, or future sources,
 * but callers always receive domain models wrapped in [Resource] for consistent error handling.
 */
interface MusicRepository {

    /**
     * Retrieves all indexed audio tracks available to the app.
     *
     * @return [Resource.Success] with the full track list,
     *         [Resource.Error] on indexed-library access failure.
     */
    suspend fun getTracks(): Resource<List<Track>>

    /**
     * Retrieves all indexed albums available to the app.
     *
     * @return [Resource.Success] with the album list,
     *         [Resource.Error] on indexed-library access failure.
     */
    suspend fun getAlbums(): Resource<List<Album>>

    /**
     * Retrieves all indexed artists available to the app.
     *
     * @return [Resource.Success] with the artist list,
     *         [Resource.Error] on indexed-library access failure.
     */
    suspend fun getArtists(): Resource<List<Artist>>

    /**
     * Searches indexed tracks by title, artist, or album name.
     *
     * @param query Case-insensitive search string.
     * @return [Resource.Success] with matching tracks,
     *         [Resource.Error] on indexed-library access failure.
     */
    suspend fun searchTracks(query: String): Resource<List<Track>>
}

