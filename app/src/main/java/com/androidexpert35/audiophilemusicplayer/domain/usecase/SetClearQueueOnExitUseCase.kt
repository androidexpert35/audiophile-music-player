package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.repository.SettingsRepository
import com.tony.coreui.domain.resource.Resource

/**
 * Persists the listener's queue-retention policy for app task removal.
 *
 * @property settingsRepository Persistent source of the queue-retention preference.
 */
class SetClearQueueOnExitUseCase(
    private val settingsRepository: SettingsRepository
) {

    /** @return The result of saving the desired task-removal queue policy. */
    suspend operator fun invoke(enabled: Boolean): Resource<Unit> =
        settingsRepository.setClearQueueOnExit(enabled)
}
