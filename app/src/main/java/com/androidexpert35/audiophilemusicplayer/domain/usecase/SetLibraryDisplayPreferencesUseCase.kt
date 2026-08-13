package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.model.library.LibraryDisplayPreferences
import com.androidexpert35.audiophilemusicplayer.domain.repository.SettingsRepository
import com.tony.coreui.domain.resource.Resource

/** Persists the current sort and layout selections for all library sections. */
class SetLibraryDisplayPreferencesUseCase(
    private val settingsRepository: SettingsRepository,
) {
    /** @return Success once the complete preference snapshot has been committed. */
    suspend operator fun invoke(preferences: LibraryDisplayPreferences): Resource<Unit> =
        settingsRepository.setLibraryDisplayPreferences(preferences)
}
