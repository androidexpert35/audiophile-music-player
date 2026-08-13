package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.model.library.LibraryDisplayPreferences
import com.androidexpert35.audiophilemusicplayer.domain.repository.SettingsRepository
import com.tony.coreui.domain.resource.Resource

/** Retrieves the saved sort and layout selections for all library sections. */
class GetLibraryDisplayPreferencesUseCase(
    private val settingsRepository: SettingsRepository,
) {
    /** @return The saved display preferences, or a storage error. */
    suspend operator fun invoke(): Resource<LibraryDisplayPreferences> =
        settingsRepository.getLibraryDisplayPreferences()
}
