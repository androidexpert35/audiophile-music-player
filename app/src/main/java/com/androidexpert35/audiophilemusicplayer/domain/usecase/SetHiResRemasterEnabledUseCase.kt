package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.repository.SettingsRepository
import com.tony.coreui.domain.resource.Resource

/**
 * Persists the "Hi-Res Dynamic Remaster enabled" user preference.
 *
 * The change takes effect on the next track load in the audiophile engine.
 * Lossy sources are always unaffected regardless of this setting.
 *
 * @property settingsRepository Persistent settings store.
 * @constructor Creates the use case with its required repository dependency.
 */
class SetHiResRemasterEnabledUseCase(
    private val settingsRepository: SettingsRepository,
) {
    /**
     * Persists the Hi-Res Dynamic Remaster enabled preference.
     *
     * @param enabled `true` to activate 96 kHz oversampling and dynamic
     *   expansion for lossless sources; `false` to disable.
     * @return [Resource.Success] when the value is stored successfully,
     *   [Resource.Error] when the preference file could not be written.
     */
    suspend operator fun invoke(enabled: Boolean): Resource<Unit> =
        settingsRepository.setHiResRemasterEnabled(enabled)
}

