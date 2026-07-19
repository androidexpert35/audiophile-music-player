package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow

/**
 * Observes the "Hi-Res Dynamic Remaster enabled" preference as a live stream.
 *
 * Returns a [Flow] that emits the current persisted toggle value immediately
 * on subscription and then on every subsequent change, enabling the Settings
 * screen to stay in sync without polling.
 *
 * @property settingsRepository Persistent settings store.
 * @constructor Creates the use case with its required repository dependency.
 */
class ObserveHiResRemasterEnabledUseCase(
    private val settingsRepository: SettingsRepository,
) {
    /**
     * Returns a [Flow] emitting the current "Hi-Res Dynamic Remaster enabled"
     * toggle state on each change.
     *
     * @return Continuous [Flow] of `Boolean` — `true` when Hi-Res Dynamic
     *   Remaster is enabled for lossless sources.
     */
    operator fun invoke(): Flow<Boolean> =
        settingsRepository.observeHiResRemasterEnabled()
}

