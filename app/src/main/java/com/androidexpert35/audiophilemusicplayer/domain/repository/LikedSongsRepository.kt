package com.androidexpert35.audiophilemusicplayer.domain.repository

import com.tony.coreui.domain.resource.Resource
import kotlinx.coroutines.flow.Flow

/**
 * Abstraction over the local liked-songs store.
 *
 * Persists track IDs that the user has liked and exposes them as reactive flows
 * so any observer (library screen, player screen) stays in sync without polling.
 */
interface LikedSongsRepository {

    /**
     * Returns a live stream of liked track IDs.
     *
     * Emits immediately with the current set and then on every change, so
     * composables and ViewModels only need to `collect` once.
     *
     * @return Cold [Flow] that emits the full [Set] of liked track IDs on every update.
     */
    fun observeLikedSongIds(): Flow<Set<Long>>

    /**
     * Reads the current set of liked track IDs without establishing an observation.
     *
     * Useful for one-shot checks (e.g., computing an initial state before the
     * Flow subscription is active).
     *
     * @return [Resource.Success] with the set of liked IDs, or [Resource.Error] on failure.
     */
    suspend fun getLikedTrackIds(): Resource<Set<Long>>

    /**
     * Toggles the like status of a track.
     *
     * If the track is currently liked it is unliked; if it is not liked it is liked.
     *
     * @param trackId Stable MediaStore identifier of the track to toggle.
     * @return [Resource.Success] on a successful write, [Resource.Error] otherwise.
     */
    suspend fun toggleLike(trackId: Long): Resource<Unit>
}

