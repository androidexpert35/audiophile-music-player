package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.repository.SettingsRepository
import com.tony.coreui.domain.resource.Resource

/**
 * Persists the "Sonic Upscaling Enhancer enabled" user preference.
 *
 * The change takes effect on the next track load in the audiophile engine.
 * Lossless sources are always played bit-perfect regardless of this setting.
 *
 * @property settingsRepository Persistent settings store.
 * @constructor Creates the use case with its required repository dependency.
 */
class SetSueEnabledUseCase(
    private val settingsRepository: SettingsRepository,
) {
    /**
     * Persists the SUE enabled preference.
     *
     * @param enabled `true` to enable the Sonic Upscaling Enhancer for
     *   lossy-compressed sources; `false` to disable.
     * @return [Resource.Success] when the value is stored successfully,
     *   [Resource.Error] when the preference file could not be written.
     */
    suspend operator fun invoke(enabled: Boolean): Resource<Unit> =
        settingsRepository.setSueEnabled(enabled)
}

