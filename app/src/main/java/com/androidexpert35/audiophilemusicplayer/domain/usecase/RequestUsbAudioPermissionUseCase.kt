package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.repository.SettingsRepository
import com.tony.coreui.domain.resource.Resource

/**
 * Triggers a USB host permission request for the connected DAC.
 *
 * @property settingsRepository Repository coordinating the permission request.
 */
class RequestUsbAudioPermissionUseCase(
    private val settingsRepository: SettingsRepository
) {
    /**
     * @return [Resource.Success] when the permission dialog was dispatched,
     *   otherwise [Resource.Error].
     */
    suspend operator fun invoke(): Resource<Unit> = settingsRepository.requestUsbAudioPermission()
}

