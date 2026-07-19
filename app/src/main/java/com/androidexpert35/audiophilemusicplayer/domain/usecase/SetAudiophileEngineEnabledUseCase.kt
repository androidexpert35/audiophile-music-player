package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.repository.SettingsRepository
import com.tony.coreui.domain.resource.Resource

/**
 * Persists the "audiophile engine enabled" user preference.
 *
 * @property settingsRepository Persistent settings store.
 */
class SetAudiophileEngineEnabledUseCase(
    private val settingsRepository: SettingsRepository
) {
    /**
     * @param enabled `true` to prefer the bit-perfect FFmpeg engine,
     *   `false` to use the battery-saving ExoPlayer engine.
     * @return [Resource.Success] when the engine swap and persistence both
     *   complete successfully, otherwise [Resource.Error].
     */
    suspend operator fun invoke(enabled: Boolean): Resource<Unit> =
        settingsRepository.setAudiophileEngineEnabled(enabled)
}

