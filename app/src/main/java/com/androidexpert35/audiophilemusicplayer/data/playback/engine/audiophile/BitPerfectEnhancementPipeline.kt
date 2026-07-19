
package com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile

import android.media.AudioFormat
import android.util.Log
import com.androidexpert35.audiophilemusicplayer.data.playback.native_.AudioFormatInfo
import com.androidexpert35.audiophilemusicplayer.data.playback.native_.FFmpegDecoder
import com.androidexpert35.audiophilemusicplayer.data.playback.native_.SueInfo

// ── Enhancement-stage decision constants ─────────────────────────────────────

private const val TAG = "AudiophileEngine"

/**
 * Neutral ReplayGain value passed through paths where the gain stage is inactive.
 * Kept equal to [SueStage.DEFAULT_REPLAYGAIN_DB] (−3 dB, assume peak = 1.0) so a
 * value that accidentally reaches the Hi-Res chain still behaves safely.
 */
internal const val REPLAYGAIN_NEUTRAL_DB = -3.0f

// ── Stage routing predicates ──────────────────────────────────────────────────

/**
 * Returns `true` when the given [format] should be processed by an active
 * enhancement stage (SUE for lossy sources, Hi-Res Remaster for lossless).
 *
 * Note: DSD sources (isDsd=true) are already handled by the bit-perfect DSD
 * pipeline; this predicate is only meaningful for PCM-decoded streams.
 *
 * @param format     Decoded source format for the current track.
 * @param sueEnabled Whether the Sonic Upscaling Enhancer toggle is on.
 * @param hiResEnabled Whether the Hi-Res Dynamic Remaster toggle is on.
 * @return `true` when a [SueStage] should be created for this format.
 */
internal fun shouldUseSueStage(
    format: AudioFormatInfo,
    sueEnabled: Boolean,
    hiResEnabled: Boolean = false,
): Boolean {
    val resolution = SueProfileResolver.resolve(format)
    return if (resolution.isLossySource) {
        // Lossy path: SUE engine, gated on the SUE toggle and profile matrix.
        sueEnabled && resolution.intensityProfile != SueIntensityProfile.BYPASS
    } else {
        // Lossless path: Hi-Res Remaster engine, gated on the Hi-Res toggle.
        // Sources that are already at native hi-res quality (≥24-bit or >48 kHz)
        // need no oversampling — the remaster stage would be ineffective and is
        // unconditionally bypassed regardless of the user preference.
        hiResEnabled && !format.isAlreadyNativeHiRes()
    }
}

/**
 * Returns `true` when [format] will be processed by the Hi-Res Dynamic
 * Remaster stage and is therefore eligible to consume ReplayGain metadata.
 *
 * @param format       Decoded source format.
 * @param hiResEnabled Whether the Hi-Res Dynamic Remaster toggle is on.
 */
internal fun shouldUseHiResRemasterStage(
    format: AudioFormatInfo,
    hiResEnabled: Boolean,
): Boolean {
    val resolution = SueProfileResolver.resolve(format)
    return !resolution.isLossySource && hiResEnabled && !format.isAlreadyNativeHiRes()
}

/**
 * Returns the ReplayGain value for the current decoder only when the Hi-Res
 * Dynamic Remaster path will consume it for [format].
 *
 * Every other path (plain passthrough, force-48k, lossy SUE, DSD, DSD PCM
 * fallback) must remain fully ReplayGain-free to avoid an unintended gain stage.
 *
 * @param decoder      Open [FFmpegDecoder] for the current track.
 * @param format       Decoded source format.
 * @param hiResEnabled Whether the Hi-Res Dynamic Remaster toggle is on.
 * @return Album/track ReplayGain in dB read from file tags, or
 *   [REPLAYGAIN_NEUTRAL_DB] when the Hi-Res path is inactive.
 */
internal fun resolveReplayGainDbIfNeeded(
    decoder: FFmpegDecoder,
    format: AudioFormatInfo,
    hiResEnabled: Boolean,
): Float = if (shouldUseHiResRemasterStage(format, hiResEnabled)) {
    decoder.getReplayGainDb()
} else {
    REPLAYGAIN_NEUTRAL_DB
}

/**
 * Returns the [AudioFormatInfo] to pass to the sink factory when [sueStage]
 * is active.
 *
 * When a [SueStage] is engaged the downstream sink must be opened at the
 * stage's float32 output rate, not at the decoder's native source rate.
 * Using the source format would produce the wrong AudioTrack sample rate
 * and deliver either silence or pitch-shifted audio.
 *
 * Returns [format] unchanged when [sueStage] is inactive or `null`.
 *
 * @param format   Decoded source format as reported by [FFmpegDecoder].
 * @param sueStage Active enhancement stage, or `null` for passthrough paths.
 * @return Format descriptor appropriate for opening the output sink.
 */
internal fun pipelineOutputFormat(
    format: AudioFormatInfo,
    sueStage: SueStage?,
): AudioFormatInfo = if (sueStage?.isActive == true) {
    format.copy(
        sampleRateHz = sueStage.outputSampleRateHz,
        androidPcmEncoding = AudioFormat.ENCODING_PCM_FLOAT,
        bytesPerSample = Float.SIZE_BYTES,
        // Reflect the float32 carrier depth in telemetry so settings UI shows
        // 32-bit instead of the original decoder bit depth (e.g., 24-bit FLAC).
        sourceBitDepth = Float.SIZE_BYTES * 8,
    )
} else {
    format
}

/**
 * Resolves the playback-head clock rate from the fully negotiated output path.
 *
 * The frame counter on the [AudiophileOutputSink] advances at the rate at which
 * the sink was opened — which is the SUE output rate when active, the DoP PCM
 * carrier rate for DSD, the full one-bit DSD rate for native DSD passthrough,
 * or the source rate for PCM passthrough. Using the wrong rate causes the seek
 * bar to drift from real time — e.g. native DSD passthrough previously fell
 * through to the FFmpeg-reported byte rate (source rate ÷ 8), which made the
 * seek bar advance 8× too fast because both [LibusbDsdAudioSink] and
 * [UsbAudioSink] count their playhead in one-bit DSD frames at the full
 * [DsdPlaybackContext.effectiveRate] rate, not the byte rate.
 *
 * @param format             Decoded source format.
 * @param dsdPlaybackContext Active DSD transport context, or `null` for PCM.
 * @param sueStage           Active enhancement stage, or `null` for passthrough.
 * @param sinkClockRateHz    Sink-owned playhead clock when its frame unit
 *   differs from the nominal carrier.
 * @return Sample-rate clock in Hertz used for playback-position calculations.
 */
internal fun pipelinePlaybackClockRate(
    format: AudioFormatInfo,
    dsdPlaybackContext: DsdPlaybackContext?,
    sueStage: SueStage?,
    sinkClockRateHz: Int? = null,
): Int = sinkClockRateHz
    ?: dsdPlaybackContext?.dopPcmRate
    ?: dsdPlaybackContext?.effectiveRate?.sampleRateHz
    ?: sueStage?.takeIf { it.isActive }?.outputSampleRateHz
    ?: playbackClockRateFor(format, null)

// ── Stage construction ────────────────────────────────────────────────────────

/**
 * Builds a [SueStage] for the current track when the resolved pipeline requires
 * an enhancement or resampler stage, returning `null` to signal passthrough.
 *
 * Route selection priority:
 * 1. **Standalone standard-PCM resampler** — when [standaloneResampleTargetRateHz]
 *    is non-null, creates a minimal `aresample=resampler=soxr` lavfi graph that
 *    only converts the source rate to the resolved platform target. All SUE /
 *    Hi-Res Remaster logic is bypassed.
 * 2. **Lossless path** — Hi-Res Dynamic Remaster engine, gated on [hiResEnabled].
 *    Sources already at native hi-res quality (≥24-bit or >48 kHz) are skipped.
 * 3. **Lossy path** — Sonic Upscaling Enhancer, gated on [sueEnabled] and the
 *    codec profile matrix. Returns `null` when the profile is BYPASS.
 *
 * @param format                         Decoded source format.
 * @param sueEnabled                     Whether the SUE toggle is currently on.
 * @param hiResEnabled                   Whether the Hi-Res Remaster toggle is on.
 * @param desiredOutputSampleRateHz      Target carrier rate for the enhancement stage.
 * @param downstreamHqResamplerActive    `true` when libsoxr CHQ resampling is active
 *   downstream of SUE; triggers a one-step profile downgrade in the native layer.
 * @param replayGainDb                   ReplayGain dB read from file tags; forwarded
 *   to the Hi-Res Remaster engine's volume stage. Ignored for lossy SUE paths.
 * @param standaloneResampleTargetRateHz Non-null target rate when the standard PCM
 *   path needs a minimal SoXR stage rather than full SUE / Hi-Res processing.
 * @return A configured [SueStage], or `null` when no processing stage is needed.
 */
internal fun buildSueStageIfNeeded(
    format: AudioFormatInfo,
    sueEnabled: Boolean,
    hiResEnabled: Boolean,
    desiredOutputSampleRateHz: Int,
    downstreamHqResamplerActive: Boolean = false,
    replayGainDb: Float = REPLAYGAIN_NEUTRAL_DB,
    standaloneResampleTargetRateHz: Int? = null,
): SueStage? {

    // ── 1. Standalone standard-PCM resampler path ────────────────────────────
    if (standaloneResampleTargetRateHz != null && standaloneResampleTargetRateHz > 0) {
        Log.d(
            TAG,
            "Standard PCM standalone resampler: building aresample stage " +
                "${format.sampleRateHz}→${standaloneResampleTargetRateHz}Hz " +
                "codec=${format.codec.displayName}",
        )
        return SueStage(
            inputEncoding = format.androidPcmEncoding,
            channelCount = format.channelCount,
            sampleRateHz = format.sampleRateHz,
            targetSampleRateHz = standaloneResampleTargetRateHz,
            codecTier = SueCodecTier.TIER_MID,
            bitrateKbps = 0,
            isLossy = false,
            isLosslessSource = false,
            downstreamHqResamplerActive = false,
            replayGainDb = replayGainDb,
            isForce48kResampleOnly = true,
        )
    }

    val resolution = SueProfileResolver.resolve(format)

    // ── 2. Lossless path — Hi-Res Dynamic Remaster engine ───────────────────
    if (!resolution.isLossySource) {
        if (!hiResEnabled) {
            Log.d(TAG, "Hi-Res Remaster bypassed — setting disabled codec=${resolution.codecDisplayName}")
            return null
        }
        // Sources already at or above native hi-res quality gain nothing from
        // the remaster's oversampling pass — skip unconditionally.
        if (format.isAlreadyNativeHiRes()) {
            Log.d(
                TAG,
                "Hi-Res Remaster bypassed — source is already native hi-res: " +
                    "${format.sampleRateHz}Hz/${format.sourceBitDepth}-bit codec=${resolution.codecDisplayName}",
            )
            return null
        }
        return SueStage(
            inputEncoding = format.androidPcmEncoding,
            channelCount = format.channelCount,
            sampleRateHz = format.sampleRateHz,
            targetSampleRateHz = desiredOutputSampleRateHz,
            // codecTier and bitrateKbps are ignored by the C++ lossless routing path.
            codecTier = SueCodecTier.TIER_MID,
            bitrateKbps = 0,
            isLossy = false,
            isLosslessSource = true,
            // Hi-Res Remaster owns its own oversampling to 96 kHz; no
            // downstream-resampler flag needed for the lossless path.
            downstreamHqResamplerActive = false,
            replayGainDb = replayGainDb,
        )
    }

    // ── 3. Lossy path — Sonic Upscaling Enhancer ────────────────────────────
    if (!sueEnabled) return null
    if (resolution.intensityProfile == SueIntensityProfile.BYPASS) {
        Log.d(
            TAG,
            "SUE bypassed by profile matrix codec=${resolution.codecDisplayName} bitrate=${format.bitrateKbps}kbps",
        )
        return null
    }
    if (format.bitrateKbps <= 0) {
        Log.w(
            TAG,
            "SUE: bitrate metadata unavailable for codec=${resolution.codecDisplayName}, defaulting to MODERATE profile",
        )
    }

    return SueStage(
        inputEncoding = format.androidPcmEncoding,
        channelCount = format.channelCount,
        sampleRateHz = format.sampleRateHz,
        targetSampleRateHz = desiredOutputSampleRateHz,
        codecTier = resolution.codecTier,
        bitrateKbps = format.bitrateKbps,
        isLossy = true,
        isLosslessSource = false,
        downstreamHqResamplerActive = downstreamHqResamplerActive,
        specialFlags = resolution.specialFlags,
    ).also { stage ->
        BitPerfectDiagnosticsLogger.onSueStageBuilt(
            bitrateKbps = format.bitrateKbps,
            isActive = stage.isActive,
            profileResolved = resolution.intensityProfile.name,
            failureReason = stage.initFailureReason,
        )
    }
}

/**
 * Builds the [SueInfo] diagnostic snapshot for the current track.
 *
 * The snapshot describes which enhancement path is active (SUE, Hi-Res Remaster,
 * standalone SoXR stage, or none) so telemetry and the Settings screen can
 * accurately represent the current signal-processing state.
 *
 * @param format       Decoded source format for the current track.
 * @param sueStage     Active enhancement stage, or `null` for passthrough.
 * @param sueEnabled   Whether the SUE toggle is currently on.
 * @param hiResEnabled Whether the Hi-Res Dynamic Remaster toggle is on.
 * @return A fully populated [SueInfo] snapshot.
 */
internal fun buildSueInfo(
    format: AudioFormatInfo,
    sueStage: SueStage?,
    sueEnabled: Boolean,
    hiResEnabled: Boolean,
): SueInfo {
    // ── Standalone standard-PCM resampler path ──────────────────────────────
    // Represent it with explicit zeroed flags so the Settings UI shows the
    // correct card state while isForce48kResampleActive signals "SoX VHQ".
    if (sueStage?.isForce48kResampleOnly == true) {
        val resolution = SueProfileResolver.resolve(format)
        return SueInfo(
            enabled = sueEnabled,
            isActive = false,
            isLossy = resolution.isLossySource,
            isProvisioned = true,
            intensityProfile = SueIntensityProfile.BYPASS.name,
            codecDisplayName = resolution.codecDisplayName,
            isHiResRemasterEnabled = hiResEnabled,
            isHiResRemasterActive = false,
            isForce48kResampleActive = sueStage.isActive,
            failureReason = if (!sueStage.isActive) sueStage.initFailureReason else null,
        )
    }

    val resolution = SueProfileResolver.resolve(format)
    if (!resolution.isLossySource) {
        // Hi-Res Dynamic Remaster reuses the same native stage plumbing, but the
        // SUE card in Settings should describe only lossy restoration — keep the
        // two feature toggles independent in telemetry and UI semantics.
        return SueInfo(
            enabled = sueEnabled,
            isActive = false,
            isLossy = false,
            isProvisioned = true,
            intensityProfile = SueIntensityProfile.BYPASS.name,
            codecDisplayName = resolution.codecDisplayName,
            isHiResRemasterEnabled = hiResEnabled,
            isHiResRemasterActive = sueStage?.isActive == true,
            failureReason = null,
        )
    }

    val stageExpected = sueEnabled && resolution.intensityProfile != SueIntensityProfile.BYPASS

    return SueInfo(
        enabled = sueEnabled,
        isActive = sueStage?.isActive == true,
        isLossy = true,
        isProvisioned = !stageExpected || sueStage?.isActive == true,
        intensityProfile = resolution.intensityProfile.name,
        codecDisplayName = resolution.codecDisplayName,
        isHiResRemasterEnabled = hiResEnabled,
        failureReason = sueStage?.initFailureReason,
    )
}

// ── AudioFormatInfo extension ─────────────────────────────────────────────────

/**
 * Returns `true` when this decoded audio source is already at native hi-res
 * quality and the Hi-Res Dynamic Remaster stage would be ineffective.
 *
 * The remaster engine's purpose is to oversample standard-definition lossless
 * material (e.g. 16-bit/44.1 kHz CD-quality FLAC) to 88.2 / 96 kHz. That
 * oversampling pass delivers no audible benefit when:
 *
 * - The **bit depth** is already ≥ 24-bit (the source carries more resolution
 *   than the remaster step would add), **or**
 * - The **sample rate** already exceeds 48 000 Hz (the source is already in the
 *   hi-res domain; doubling a 96 kHz stream to 192 kHz is not the remaster's
 *   intent).
 *
 * DSD sources are handled by the bit-perfect DSD transport selection and never
 * reach this guard — it is only meaningful for PCM-decoded streams.
 */
internal fun AudioFormatInfo.isAlreadyNativeHiRes(): Boolean =
    sourceBitDepth >= 24 || sampleRateHz > 48_000
