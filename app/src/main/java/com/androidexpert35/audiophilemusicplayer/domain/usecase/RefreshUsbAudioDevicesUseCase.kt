package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.repository.SettingsRepository
import com.tony.coreui.domain.resource.Resource

/**
 * Re-runs USB DAC discovery so Settings can recover from missed attach state.
 *
 * @property settingsRepository Repository coordinating USB device refresh.
 */
class RefreshUsbAudioDevicesUseCase(
    private val settingsRepository: SettingsRepository
) {
    /**
     * @return [Resource.Success] when the USB device snapshot is refreshed,
     *   otherwise [Resource.Error].
     */
    suspend operator fun invoke(): Resource<Unit> = settingsRepository.refreshUsbAudioDevices()
}
