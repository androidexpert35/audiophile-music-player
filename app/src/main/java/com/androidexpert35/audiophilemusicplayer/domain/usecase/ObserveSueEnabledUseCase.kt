package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow

/**
 * Observes the "Sonic Upscaling Enhancer enabled" user preference.
 *
 * Emits the current persisted value immediately on subscription, then
 * re-emits on every subsequent user toggle. The enhancer remains silently
 * inactive for lossless sources regardless of this preference.
 *
 * @property settingsRepository Persistent settings store.
 * @constructor Creates the use case with its required repository dependency.
 */
class ObserveSueEnabledUseCase(
    private val settingsRepository: SettingsRepository,
) {
    /**
     * @return [Flow] emitting the current and subsequent values of the
     *   "SUE enabled" preference.
     */
    operator fun invoke(): Flow<Boolean> = settingsRepository.observeSueEnabled()
}

