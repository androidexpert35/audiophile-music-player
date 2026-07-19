package com.androidexpert35.audiophilemusicplayer.domain.repository

import com.tony.coreui.domain.resource.Resource
import kotlinx.coroutines.flow.Flow

/**
 * Provides local playback recency and personal play-count history.
 *
 * Each track has one aggregate entry whose timestamp is refreshed and whose
 * counter is incremented whenever a distinct playback starts.
 */
interface RecentlyPlayedRepository {

    /**
     * Returns a live stream of recently-played track IDs, ordered by most-recent first.
     *
     * Emits immediately with the current history and then on every change (e.g.
     * when a new track starts playing).
     *
     * @param limit Maximum number of distinct tracks to include in each emission.
     * @return Cold [Flow] emitting the ordered list of track IDs on every update.
     */
    fun observeRecentlyPlayedTrackIds(limit: Int): Flow<List<Long>>

    /**
     * Returns a live personal ranking limited to the supplied track collection.
     *
     * IDs are ordered by descending play count, with the most recent playback
     * resolving equal counts. Tracks that have never played are not emitted.
     *
     * @param trackIds Stable MediaStore identifiers eligible for the ranking.
     * @param limit Maximum number of ranked track IDs to include.
     * @return Cold [Flow] emitting ordered IDs whenever a play count changes.
     */
    fun observeMostPlayedTrackIds(trackIds: List<Long>, limit: Int): Flow<List<Long>>

    /**
     * Persists one playback start for [trackId] and increments its aggregate count.
     *
     * @param trackId Stable MediaStore identifier of the track that started playing.
     * @return [Resource.Success] on a successful write, [Resource.Error] otherwise.
     */
    suspend fun recordPlayed(trackId: Long): Resource<Unit>
}
