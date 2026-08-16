package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.repository.SettingsRepository
import com.tony.coreui.domain.resource.Resource

/**
 * Persists the user's chosen display order for the library sections.
 *
 * @property settingsRepository Persistent settings store.
 * @constructor Creates the use case with its required repository dependency.
 */
class SetLibrarySectionOrderUseCase(
    private val settingsRepository: SettingsRepository,
) {
    /**
     * @param order Library section names in the order they should be displayed.
     * @return [Resource.Success] when the value is stored successfully,
     *   [Resource.Error] when the preference file could not be written.
     */
    suspend operator fun invoke(order: List<String>): Resource<Unit> =
        settingsRepository.setLibrarySectionOrder(order)
}
