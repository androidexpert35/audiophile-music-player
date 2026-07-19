package com.androidexpert35.audiophilemusicplayer.domain.repository

import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioTelemetry
import kotlinx.coroutines.flow.Flow

/**
 * Abstraction over real-time audio telemetry collection.
 *
 * Implementations capture hardware-level playback metrics (sample rate,
 * bit depth, offload status) from the active audio sink and expose them
 * as a reactive stream for the presentation layer.
 */
interface AudioTelemetryRepository {

    /**
     * Observes real-time audio telemetry as a reactive stream.
     *
     * Emissions occur whenever the audio output format, offload state,
     * or buffer utilisation changes.
     *
     * @return A [Flow] emitting [AudioTelemetry] snapshots.
     */
    fun observeAudioTelemetry(): Flow<AudioTelemetry>

    /**
     * Requests an active re-query of the current ExoPlayer format and
     * pushes a refreshed [AudioTelemetry] snapshot to [observeAudioTelemetry].
     *
     * Implementations signal the playback service (which owns the live player
     * reference) to interrogate its current track selection and re-commit the
     * format data without restarting or interrupting playback.
     *
     * This is a fire-and-forget call: it schedules the reload but does not
     * suspend until telemetry is available. Callers observe new values via
     * the [observeAudioTelemetry] flow.
     */
    fun reloadTelemetry()
}

