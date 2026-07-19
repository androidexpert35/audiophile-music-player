package com.androidexpert35.audiophilemusicplayer.data.playback.engine.standard

import android.media.AudioFormat
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.androidexpert35.audiophilemusicplayer.data.playback.native_.AudioFormatInfo
import com.androidexpert35.audiophilemusicplayer.data.playback.native_.PipelinePathReport
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioCodec

/**
 * Maps Media3's selected audio [Format] into the app's shared telemetry models.
 *
 * The Standard engine cannot expose the Audiophile pipeline's direct-path sink
 * diagnostics, but it can still surface the active codec, sample rate, and bitrate
 * through the same collector-facing types.
 *
 * To keep telemetry fresh and avoid false empty/disabled UI states, this
 * mapper resolves the selected audio [Format] from `currentTracks` and enriches
 * it with metadata extracted from the active URI when Media3 omits fields such
 * as sample rate, bitrate, or PCM depth.
 */
@UnstableApi
internal fun Format.toStandardAudioFormatInfo(): AudioFormatInfo = AudioFormatInfo(
    sampleRateHz = sampleRate.takeIf { it != Format.NO_VALUE } ?: 0,
    channelCount = channelCount.takeIf { it != Format.NO_VALUE } ?: 0,
    sourceBitDepth = toTelemetryBitDepth(),
    androidPcmEncoding = toAndroidPcmEncoding(),
    bytesPerSample = toBytesPerSample(),
    durationMs = 0L,
    bitrateKbps = (bitrate.takeIf { it != Format.NO_VALUE } ?: 0)
        .coerceAtLeast(0)
        .div(1_000),
    codec = AudioCodec.fromMimeType(sampleMimeType),
    isDsd = false,
    dsdRate = null,
    dsdSourceFormat = null,
)

/**
 * Enriches Media3's selected audio format with encoded-track metadata when the
 * runtime format omits fields such as PCM encoding / bit depth.
 *
 * @param metadata Best-effort metadata extracted from the currently loaded URI.
 */
@UnstableApi
internal fun Format.toStandardAudioFormatInfo(
    metadata: StandardTrackMetadata?,
): AudioFormatInfo {
    val codec = AudioCodec.fromMimeType(sampleMimeType)
    val resolvedBitDepth = resolveTelemetryBitDepth(
        metadata = metadata,
        codec = codec,
    )
    val resolvedAndroidEncoding = resolveAndroidPcmEncoding(resolvedBitDepth)

    return AudioFormatInfo(
        sampleRateHz = sampleRate.takeIf { it != Format.NO_VALUE }
            ?: metadata?.sampleRateHz
            ?: 0,
        channelCount = channelCount.takeIf { it != Format.NO_VALUE } ?: 0,
        sourceBitDepth = resolvedBitDepth,
        androidPcmEncoding = resolvedAndroidEncoding,
        bytesPerSample = resolveBytesPerSample(resolvedBitDepth),
        durationMs = 0L,
        bitrateKbps = (bitrate.takeIf { it != Format.NO_VALUE } ?: 0)
            .coerceAtLeast(0)
            .div(1_000)
            .takeIf { it > 0 }
            ?: metadata?.bitrateKbps
            ?: 0,
        codec = codec,
        isDsd = false,
        dsdRate = null,
        dsdSourceFormat = null,
    )
}

/**
 * Builds a synthetic path report for the Standard engine.
 *
 * Direct-path and mixer-bypass flags are always `false` here because Media3's
 * battery-saving engine intentionally uses the normal platform decode pipeline.
 * [PipelinePathReport.audioSessionId] is left as `0` — session tracking is not
 * needed for telemetry in the standard pipeline.
 */
@UnstableApi
internal fun createStandardPathReport(
    format: Format,
): PipelinePathReport = PipelinePathReport(
    usedDirectFlag = false,
    usedFloatFallback = false,
    encoding = format.toAndroidPcmEncoding(),
    sampleRateHz = format.sampleRate.takeIf { it != Format.NO_VALUE } ?: 0,
    channelMask = 0,
    bufferFrames = 0,
    nativeOutputSampleRateHz = 0,
    framesPerBuffer = 0,
    routedDeviceType = 0,
    routedDeviceName = null,
    audioSessionId = 0,
    dsdPipelineInfo = null,
)

/**
 * Builds a synthetic Standard-engine path report with metadata fallback.
 *
 * @param metadata Best-effort file metadata extracted from the current URI.
 */
@UnstableApi
internal fun createStandardPathReport(
    format: Format,
    metadata: StandardTrackMetadata?,
): PipelinePathReport {
    val codec = AudioCodec.fromMimeType(format.sampleMimeType)
    val resolvedBitDepth = format.resolveTelemetryBitDepth(
        metadata = metadata,
        codec = codec,
    )

    return PipelinePathReport(
        usedDirectFlag = false,
        usedFloatFallback = false,
        encoding = format.toAndroidPcmEncoding().takeIf { it != AudioFormat.ENCODING_INVALID }
            ?: resolveAndroidPcmEncoding(resolvedBitDepth),
        sampleRateHz = format.sampleRate.takeIf { it != Format.NO_VALUE }
            ?: metadata?.sampleRateHz
            ?: 0,
        channelMask = 0,
        bufferFrames = 0,
        nativeOutputSampleRateHz = 0,
        framesPerBuffer = 0,
        routedDeviceType = 0,
        routedDeviceName = null,
        audioSessionId = 0,
        dsdPipelineInfo = null,
    )
}

/**
 * Resolves the best currently-known audio [Format] for standard-mode telemetry.
 *
 * Uses Media3's selected audio track as the primary source of truth. The
 * resulting format is then enriched elsewhere with metadata extracted from the
 * active URI when Media3 leaves fields blank.
 *
 * @return The selected audio [Format], or `null` when no audio track has been
 *         selected yet.
 */
@UnstableApi
internal fun Tracks.resolveTelemetryAudioFormat(): Format? =
    toSelectedAudioFormat()

/**
 * Extracts the selected audio [Format] from [Tracks] when available.
 *
 * This is a best-effort fallback for cases where the renderer has not yet
 * published its active audio format but Media3 already knows which audio track
 * is selected.
 *
 * @return The selected audio [Format], or `null` when none can be determined.
 */
@UnstableApi
internal fun Tracks.toSelectedAudioFormat(): Format? = groups.firstNotNullOfOrNull { group ->
    if (group.type != C.TRACK_TYPE_AUDIO) return@firstNotNullOfOrNull null

    (0 until group.length).firstNotNullOfOrNull { trackIndex ->
        group.getTrackFormat(trackIndex).takeIf { group.isTrackSelected(trackIndex) }
    }
}

private fun Format.toTelemetryBitDepth(): Int = when (pcmEncoding) {
    C.ENCODING_PCM_16BIT -> 16
    C.ENCODING_PCM_24BIT -> 24
    C.ENCODING_PCM_32BIT,
    C.ENCODING_PCM_FLOAT -> 32
    else -> 0
}

private fun Format.toAndroidPcmEncoding(): Int = when (pcmEncoding) {
    C.ENCODING_PCM_16BIT -> AudioFormat.ENCODING_PCM_16BIT
    C.ENCODING_PCM_24BIT -> AudioFormat.ENCODING_PCM_24BIT_PACKED
    C.ENCODING_PCM_32BIT -> AudioFormat.ENCODING_PCM_32BIT
    C.ENCODING_PCM_FLOAT -> AudioFormat.ENCODING_PCM_FLOAT
    else -> AudioFormat.ENCODING_INVALID
}

private fun Format.toBytesPerSample(): Int = when (pcmEncoding) {
    C.ENCODING_PCM_16BIT -> 2
    C.ENCODING_PCM_24BIT -> 3
    C.ENCODING_PCM_32BIT,
    C.ENCODING_PCM_FLOAT -> 4
    else -> 0
}

private fun Format.resolveTelemetryBitDepth(
    metadata: StandardTrackMetadata?,
    codec: AudioCodec,
): Int = toTelemetryBitDepth()
    .takeIf { it > 0 }
    ?: metadata?.bitDepth?.takeIf { it > 0 }
    ?: codec.defaultStandardBitDepth()

private fun resolveAndroidPcmEncoding(bitDepth: Int): Int = when (bitDepth) {
    in 1..16 -> AudioFormat.ENCODING_PCM_16BIT
    in 17..24 -> AudioFormat.ENCODING_PCM_24BIT_PACKED
    32 -> AudioFormat.ENCODING_PCM_32BIT
    else -> AudioFormat.ENCODING_INVALID
}

private fun resolveBytesPerSample(bitDepth: Int): Int = when (bitDepth) {
    in 1..16 -> 2
    in 17..24 -> 3
    32 -> 4
    else -> 0
}

private fun AudioCodec.defaultStandardBitDepth(): Int = when {
    this == AudioCodec.UNKNOWN -> 0
    isLossless -> 0
    else -> 16
}
