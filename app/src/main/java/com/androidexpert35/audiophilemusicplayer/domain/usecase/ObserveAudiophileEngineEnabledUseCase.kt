package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow

/**
 * Observes the "audiophile engine enabled" user preference.
 *
 * @property settingsRepository Persistent settings store.
 */
class ObserveAudiophileEngineEnabledUseCase(
    private val settingsRepository: SettingsRepository
) {
    /** @return [Flow] emitting the current and subsequent values of the preference. */
    operator fun invoke(): Flow<Boolean> = settingsRepository.observeAudiophileEngineEnabled()
}

