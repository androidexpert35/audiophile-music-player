package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.model.audio.UsbAudioStatus
import com.androidexpert35.audiophilemusicplayer.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow

/**
 * Observes the current direct USB audio availability snapshot.
 *
 * @property settingsRepository Repository exposing USB playback readiness.
 */
class ObserveUsbAudioStatusUseCase(
    private val settingsRepository: SettingsRepository
) {
    /** @return [Flow] emitting the current and subsequent USB DAC status snapshots. */
    operator fun invoke(): Flow<UsbAudioStatus> = settingsRepository.observeUsbAudioStatus()
}

