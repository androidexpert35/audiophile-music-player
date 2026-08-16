package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.model.library.LibraryDisplayPreferences
import com.androidexpert35.audiophilemusicplayer.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow

/**
 * Observes the saved sort, layout, and visibility selections for all library sections.
 *
 * Emits the current persisted preferences immediately on subscription, then re-emits
 * on every subsequent change — unlike [GetLibraryDisplayPreferencesUseCase]'s one-shot
 * read, this lets a section hidden or reordered from Settings apply to an
 * already-composed Library screen.
 *
 * @property settingsRepository Persistent settings store.
 * @constructor Creates the use case with its required repository dependency.
 */
class ObserveLibraryDisplayPreferencesUseCase(
    private val settingsRepository: SettingsRepository,
) {
    /** @return [Flow] emitting the current and subsequent library display preferences. */
    operator fun invoke(): Flow<LibraryDisplayPreferences> =
        settingsRepository.observeLibraryDisplayPreferences()
}
