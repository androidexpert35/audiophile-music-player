package com.androidexpert35.audiophilemusicplayer.domain.repository

import com.androidexpert35.audiophilemusicplayer.domain.model.playback.PersistedPlaybackState
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track
import com.tony.coreui.domain.resource.Resource

/**
 * Abstraction over the playback-session persistence store.
 *
 * Defines read/write access to the last saved session snapshot and a lightweight
 * track-lookup helper used during queue restoration. Implementations are responsible
 * for dispatching all I/O off the main thread.
 */
interface PlaybackPersistenceRepository {

    /**
     * Persists the current playback session snapshot so it can be restored on
     * the next app launch.
     *
     * @param state The session snapshot to save.
     * @return [Resource.Success] on a successful write,
     *         [Resource.Error] with a [com.tony.coreui.domain.resource.ResourceError.DatabaseError]
     *         if the write fails.
     */
    suspend fun savePlaybackState(state: PersistedPlaybackState): Resource<Unit>

    /**
     * Reads the last persisted playback session.
     *
     * @return [Resource.Success] wrapping the saved [PersistedPlaybackState], or `null`
     *         when no session has been saved yet. Returns [Resource.Error] only on
     *         unexpected storage failures.
     */
    suspend fun restorePlaybackState(): Resource<PersistedPlaybackState?>

    /**
     * Fetches the full [Track] domain models for a given ordered list of IDs.
     *
     * Used exclusively during queue restoration to map the stored ID list back into
     * playable tracks without re-querying MediaStore. Tracks not found in the Room
     * index (e.g. deleted files) are silently omitted from the result.
     *
     * @param ids Ordered list of MediaStore track identifiers to look up.
     * @return [Resource.Success] with the matching [Track] list (may be shorter than [ids]
     *         if some tracks have been removed from the library), or [Resource.Error] on failure.
     */
    suspend fun getTracksForQueue(ids: List<Long>): Resource<List<Track>>
}

