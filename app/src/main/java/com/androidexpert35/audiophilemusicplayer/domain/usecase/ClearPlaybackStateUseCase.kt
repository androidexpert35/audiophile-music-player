package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.repository.PlaybackPersistenceRepository
import com.tony.coreui.domain.resource.Resource

/**
 * Removes the restorable playback session when the listener opts not to retain a queue.
 *
 * @property playbackPersistenceRepository Store owning the singleton session snapshot.
 */
class ClearPlaybackStateUseCase(
    private val playbackPersistenceRepository: PlaybackPersistenceRepository
) {

    /** @return The result of deleting the persisted playback session. */
    suspend operator fun invoke(): Resource<Unit> = playbackPersistenceRepository.clearPlaybackState()
}
