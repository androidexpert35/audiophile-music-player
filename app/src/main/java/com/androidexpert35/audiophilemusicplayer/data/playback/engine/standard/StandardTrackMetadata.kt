package com.androidexpert35.audiophilemusicplayer.data.playback.engine.standard

/**
 * Best-effort file metadata used to enrich Standard-engine telemetry.
 *
 * Media3's selected audio format does not always expose bit depth for compressed
 * streams, so the Standard engine supplements runtime telemetry with lightweight
 * per-track metadata extracted from the currently loaded URI.
 *
 * @property sampleRateHz Encoded track sample rate in hertz, or `0` when unknown.
 * @property bitDepth Encoded track bit depth, or `0` when unavailable.
 * @property bitrateKbps Encoded bitrate in kilobits per second, or `0` when unavailable.
 */
internal data class StandardTrackMetadata(
    val sampleRateHz: Int,
    val bitDepth: Int,
    val bitrateKbps: Int,
)

