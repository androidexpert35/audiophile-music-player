package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow

/**
 * Observes the user's chosen display order for the library sections.
 *
 * Emits the current persisted order immediately on subscription, then re-emits on
 * every subsequent reorder.
 *
 * @property settingsRepository Persistent settings store.
 * @constructor Creates the use case with its required repository dependency.
 */
class ObserveLibrarySectionOrderUseCase(
    private val settingsRepository: SettingsRepository,
) {
    /** @return [Flow] emitting the current and subsequent library section order. */
    operator fun invoke(): Flow<List<String>> = settingsRepository.observeLibrarySectionOrder()
}
