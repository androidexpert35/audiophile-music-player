package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioTelemetry
import com.androidexpert35.audiophilemusicplayer.domain.repository.AudioTelemetryRepository
import kotlinx.coroutines.flow.Flow

/**
 * Observes real-time audio telemetry from the playback hardware.
 *
 * Provides the presentation layer with live data about the actual output
 * sample rate, bit depth, codec, offload status, and buffer utilisation.
 *
 * @property audioTelemetryRepository Repository collecting hardware-level metrics.
 */
class ObserveAudioTelemetryUseCase(
    private val audioTelemetryRepository: AudioTelemetryRepository
) {
    /**
     * @return A [Flow] emitting [AudioTelemetry] snapshots whenever output format changes.
     */
    operator fun invoke(): Flow<AudioTelemetry> =
        audioTelemetryRepository.observeAudioTelemetry()
}

