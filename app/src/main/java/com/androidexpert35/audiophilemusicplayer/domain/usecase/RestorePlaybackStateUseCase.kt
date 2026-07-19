package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.model.playback.PersistedPlaybackState
import com.androidexpert35.audiophilemusicplayer.domain.repository.PlaybackPersistenceRepository
import com.tony.coreui.domain.resource.Resource

/**
 * Retrieves the last persisted playback session for engine re-hydration on startup.
 *
 * Returns `null` wrapped in [Resource.Success] when no session has been saved yet
 * (e.g. first launch), allowing callers to treat the absence of data as a normal
 * no-op rather than an error condition.
 *
 * @property playbackPersistenceRepository Repository that reads the session snapshot from
 *   the local persistence store.
 * @constructor Creates the use case with its required repository dependency.
 */
class RestorePlaybackStateUseCase(
    private val playbackPersistenceRepository: PlaybackPersistenceRepository
) {

    /**
     * Loads the last saved playback session.
     *
     * @return [Resource.Success] wrapping the saved [PersistedPlaybackState], or `null`
     *         on first launch / after the data has been cleared.
     *         Returns [Resource.Error] only on unexpected storage failures.
     * @see PlaybackPersistenceRepository.restorePlaybackState
     */
    suspend operator fun invoke(): Resource<PersistedPlaybackState?> =
        playbackPersistenceRepository.restorePlaybackState()
}

