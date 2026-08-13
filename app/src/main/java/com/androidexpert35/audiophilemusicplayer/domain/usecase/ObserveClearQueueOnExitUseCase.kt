package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow

/**
 * Observes the listener's preference for clearing the queue when the app task is dismissed.
 *
 * @property settingsRepository Persistent source of the queue-retention preference.
 */
class ObserveClearQueueOnExitUseCase(
    private val settingsRepository: SettingsRepository
) {

    /** @return A stream of the persisted task-removal queue policy. */
    operator fun invoke(): Flow<Boolean> = settingsRepository.observeClearQueueOnExit()
}
