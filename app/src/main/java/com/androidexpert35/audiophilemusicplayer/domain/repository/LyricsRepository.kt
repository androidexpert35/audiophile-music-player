package com.androidexpert35.audiophilemusicplayer.domain.repository

import com.androidexpert35.audiophilemusicplayer.domain.model.lyrics.Lyrics
import com.tony.coreui.domain.resource.Resource

/**
 * Abstraction over the lyrics data source.
 *
 * Implementations may combine a local Room cache with a remote API lookup.
 * The domain layer only depends on this interface, keeping lyrics fetching
 * independent of Retrofit, Room, or any Android framework type.
 */
interface LyricsRepository {

    /**
     * Fetches lyrics for the given track metadata.
     *
     * Implementations are expected to check a local cache first and only hit
     * the network on a cache miss. A `404` or empty response from the remote
     * service should surface as [Resource.Success] with an empty [Lyrics], not
     * as an error, so the UI can differentiate "not found" from a real failure.
     *
     * @param trackTitle Display title of the track.
     * @param artistName Performing artist name.
     * @param albumName Album title.
     * @param durationSeconds Track duration rounded to the nearest second.
     * @return [Resource.Success] carrying the resolved [Lyrics] on success,
     *         [Resource.Error] when a network or storage failure occurs.
     */
    suspend fun getLyrics(
        trackTitle: String,
        artistName: String,
        albumName: String,
        durationSeconds: Int,
    ): Resource<Lyrics>
}

