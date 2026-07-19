package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.model.playback.PersistedPlaybackState
import com.androidexpert35.audiophilemusicplayer.domain.repository.PlaybackPersistenceRepository
import com.tony.coreui.domain.resource.Resource

/**
 * Persists the current playback session so it can be restored on the next app launch.
 *
 * Delegates entirely to [PlaybackPersistenceRepository] and adds no additional business
 * logic, acting as the canonical domain entry point for session-save operations.
 *
 * @property playbackPersistenceRepository Repository that writes the session snapshot to
 *   the local persistence store.
 * @constructor Creates the use case with its required repository dependency.
 */
class SavePlaybackStateUseCase(
    private val playbackPersistenceRepository: PlaybackPersistenceRepository
) {

    /**
     * Saves [state] to the local persistence store.
     *
     * @param state The current playback session snapshot to persist.
     * @return [Resource.Success] on a successful write,
     *         [Resource.Error] when the underlying store rejects the operation.
     * @see PlaybackPersistenceRepository.savePlaybackState
     */
    suspend operator fun invoke(state: PersistedPlaybackState): Resource<Unit> =
        playbackPersistenceRepository.savePlaybackState(state)
}

