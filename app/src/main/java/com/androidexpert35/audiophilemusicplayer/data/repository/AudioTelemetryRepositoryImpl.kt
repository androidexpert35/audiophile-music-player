package com.androidexpert35.audiophilemusicplayer.data.repository

import com.androidexpert35.audiophilemusicplayer.data.playback.AudioTelemetryCollector
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioTelemetry
import com.androidexpert35.audiophilemusicplayer.domain.repository.AudioTelemetryRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [AudioTelemetryRepository] implementation that surfaces real-time
 * hardware telemetry collected by the shared [AudioTelemetryCollector].
 *
 * @property telemetryCollector Singleton collector registered as an
 *           observer of the active playback engine's mirrored telemetry flows.
 */
@Singleton
class AudioTelemetryRepositoryImpl @Inject constructor(
    private val telemetryCollector: AudioTelemetryCollector
) : AudioTelemetryRepository {

    override fun observeAudioTelemetry(): Flow<AudioTelemetry> =
        telemetryCollector.telemetry

    /**
     * Fires the reload signal that [AudioTelemetryCollector.reloadRequested]
     * exposes. The current playback stack keeps telemetry live continuously,
     * so this is retained only for compatibility with older callers that still
     * trigger a best-effort refresh signal.
     */
    override fun reloadTelemetry() {
        telemetryCollector.requestReload()
    }
}

