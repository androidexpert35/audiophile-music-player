// ✅ EXTRACTED FROM: BitPerfectPlaybackEngine.kt
package com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile

import android.util.Log
import com.androidexpert35.audiophilemusicplayer.data.playback.AUDIPHILE_PATH_TAG
import com.androidexpert35.audiophilemusicplayer.data.playback.Decision
import com.androidexpert35.audiophilemusicplayer.data.playback.PathType
import com.androidexpert35.audiophilemusicplayer.data.playback.native_.AudioFormatInfo
import com.androidexpert35.audiophilemusicplayer.data.playback.native_.FFmpegDecoder
import com.androidexpert35.audiophilemusicplayer.data.playback.usb.UsbDacPresenceDetector
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bundle of artefacts produced by a successful tiered load attempt.
 *
 * @property decoder                         Open [FFmpegDecoder] for the track.
 * @property format                          Decoded format as reported by the decoder.
 * @property sink                            Ready-to-play output sink.
 * @property dsdContext                      Active DSD transport context, or `null` for PCM.
 * @property sueStage                        Active enhancement stage, or `null` when bypassed.
 * @property pathType                        High-level path classification for this session.
 * @property decision                        Rate-resolution decision associated with [pathType].
 * @property activeOutputThreadSampleRateHz  Best-effort AudioFlinger output-thread rate, or `0`.
 */
internal data class LoadedAudioSession(
    val decoder: FFmpegDecoder,
    val format: AudioFormatInfo,
    val sink: AudiophileOutputSink,
    val dsdContext: DsdPlaybackContext?,
    val sueStage: SueStage?,
    val pathType: PathType,
    val decision: Decision,
    val activeOutputThreadSampleRateHz: Int,
)

/**
 * Executes the three-tier session-building chain for [BitPerfectPlaybackEngine].
 *
 * Tier logic:
 * - **Tier 1+2 (bit-perfect)** — opens the decoder in native mode; for PCM tracks
 *   builds the enhancement stage and most suitable sink; for DSD tracks tries
 *   every bit-perfect candidate (native DSD then DoP) in preference order.
 * - **Tier 3 (PCM fallback)** — reopens the decoder with `forcePcm=true`; DSD is
 *   decimated to 88 200 Hz float32 entirely inside the FFmpeg lavfi pipeline.
 *
 * Session loading is deliberately separated from [BitPerfectPlaybackEngine] so
 * the engine class can focus on state-machine coordination and the write loop
 * while this class owns only the "open decoder → negotiate sink" concern.
 *
 * @property sinkRouter         Sink factory and DSD context router.
 * @property ratePolicy         PCM sample-rate ownership resolver.
 * @property usbDacPresenceDetector Detects whether a USB DAC is physically connected.
 */
@Singleton
internal class BitPerfectSessionLoader @Inject internal constructor(
    private val sinkRouter: BitPerfectSinkRouter,
    private val ratePolicy: BitPerfectPcmRatePolicy,
    private val usbDacPresenceDetector: UsbDacPresenceDetector,
) {

    private companion object {
        const val PATH_TAG = AUDIPHILE_PATH_TAG
    }

    /**
     * Tier 1 + Tier 2 attempt: opens the decoder in native (DSD-preserving) mode
     * and builds the matching bit-perfect sink (native DSD, DoP, or platform PCM).
     *
     * Returns `null` silently on any failure so the caller can degrade to the
     * PCM fallback tier. No UI error is reported here by design.
     *
     * @param path         Filesystem or `/proc/self/fd/<n>` path for the source file.
     * @param sueEnabled   Whether the Sonic Upscaling Enhancer is currently enabled.
     * @param hiResEnabled Whether the Hi-Res Dynamic Remaster is currently enabled.
     * @return A fully populated [LoadedAudioSession], or `null` on failure.
     */
    internal fun tryLoadBitPerfectSession(
        path: String,
        sueEnabled: Boolean,
        hiResEnabled: Boolean,
    ): LoadedAudioSession? {
        val decoder = FFmpegDecoder()
        val format = runCatching { decoder.open(path, forcePcm = false) }.getOrElse {
            Log.w(PATH_TAG, "tryLoadBitPerfectSession: decoder.open failed path=$path err=${it.message}")
            decoder.close()
            return null
        }

        Log.i(
            PATH_TAG,
            "tryLoadBitPerfectSession: opened decoder codec=${format.codec.displayName} " +
                "isDsd=${format.isDsd} sampleRateHz=${format.sampleRateHz} " +
                "sourceBitDepth=${format.sourceBitDepth} dsdRate=${format.dsdRate?.displayName} " +
                "durationMs=${format.durationMs} path=$path"
        )

        if (!format.isDsd) {
            // Plain PCM source. Resolve the enhancement stage, then pick the best sink.
            BitPerfectDiagnosticsLogger.onDecoderOpened(format, source = "T1")
            Log.d(
                PATH_TAG,
                "tryLoadBitPerfectSession[PCM]: isLibusbOutputActive=${sinkRouter.isLibusbOutputActive()} " +
                    "effectiveSue=$sueEnabled effectiveHiRes=$hiResEnabled"
            )
            val isUsbDacPresent = usbDacPresenceDetector.isUsbDacPresent()
            val ratePlan = ratePolicy.resolvePcmSampleRatePlan(
                format = format,
                sueEnabled = sueEnabled,
                hiResEnabled = hiResEnabled,
                isUsbDacPresent = isUsbDacPresent,
            )
            val sueStage = buildSueStageIfNeeded(
                format = format,
                sueEnabled = sueEnabled,
                hiResEnabled = hiResEnabled,
                desiredOutputSampleRateHz = ratePlan.sueOutputRateHz,
                replayGainDb = resolveReplayGainDbIfNeeded(
                    decoder = decoder,
                    format = format,
                    hiResEnabled = hiResEnabled,
                ),
                standaloneResampleTargetRateHz = ratePlan.standaloneResampleTargetRateHz,
            )
            val sinkFormat = pipelineOutputFormat(format, sueStage)
            val sink = runCatching {
                sinkRouter.createSinkForTrackFormat(sinkFormat, null, path, sueStage)
            }.getOrElse {
                Log.w(PATH_TAG, "tryLoadBitPerfectSession[PCM]: createSinkForTrackFormat failed: ${it.message}", it)
                sueStage?.close()
                decoder.close()
                return null
            }
            Log.i(PATH_TAG, "tryLoadBitPerfectSession[PCM]: sink=${sink.javaClass.simpleName}")
            return LoadedAudioSession(
                decoder = decoder,
                format = format,
                sink = sink,
                dsdContext = null,
                sueStage = sueStage,
                pathType = ratePlan.pathType,
                decision = ratePlan.decision,
                activeOutputThreadSampleRateHz = ratePlan.activeOutputThreadSampleRateHz,
            )
        }

        // DSD source — try every bit-perfect candidate in preference order.
        // USB device state is inspected by buildBitPerfectDsdCandidates internally.
        val candidates = sinkRouter.buildBitPerfectDsdCandidates(format)
        Log.i(
            PATH_TAG,
            "tryLoadBitPerfectSession[DSD]: dsdRate=${format.dsdRate?.displayName} " +
                "candidateCount=${candidates.size} " +
                "candidates=${candidates.map { it.outputMode::class.simpleName }}"
        )

        if (candidates.isEmpty()) {
            Log.w(PATH_TAG, "tryLoadBitPerfectSession[DSD]: no DSD candidates → falling back to PCM tier-3")
            decoder.close()
            return null
        }

        for (candidate in candidates) {
            Log.i(
                PATH_TAG,
                "tryLoadBitPerfectSession[DSD]: trying candidate ${candidate.outputMode::class.simpleName} " +
                    "effectiveRate=${candidate.effectiveRate.displayName} sourceRate=${candidate.sourceRate.displayName}"
            )
            val sink = runCatching {
                sinkRouter.createSinkForTrackFormat(format, candidate, path)
            }.getOrElse { err ->
                Log.w(
                    PATH_TAG,
                    "tryLoadBitPerfectSession[DSD]: candidate ${candidate.outputMode::class.simpleName} FAILED: " +
                        "${err.javaClass.simpleName}: ${err.message}",
                    err
                )
                null
            }
            if (sink != null) {
                Log.i(
                    PATH_TAG,
                    "tryLoadBitPerfectSession[DSD]: candidate ${candidate.outputMode::class.simpleName} " +
                        "SUCCEEDED sink=${sink.javaClass.simpleName}"
                )
                // DSD transports (native DSD, DoP) never go through SUE.
                return LoadedAudioSession(
                    decoder = decoder,
                    format = format,
                    sink = sink,
                    dsdContext = candidate,
                    sueStage = null,
                    pathType = PathType.DSD,
                    decision = Decision.Bypass,
                    activeOutputThreadSampleRateHz = 0,
                )
            }
            Log.w(
                PATH_TAG,
                "tryLoadBitPerfectSession[DSD]: candidate ${candidate.outputMode::class.simpleName} rejected — trying next",
            )
        }

        Log.w(
            PATH_TAG,
            "tryLoadBitPerfectSession[DSD]: ALL bit-perfect DSD candidates failed → " +
                "falling back to tier-3 PCM decimation (88.2 kHz float32)"
        )
        decoder.close()
        return null
    }

    /**
     * Tier 3 attempt: opens the decoder with `forcePcm=true` so DSD sources are
     * decoded to float PCM, then builds a standard platform sink.
     *
     * DSD sources are decimated to float32 at 88 200 Hz entirely inside the FFmpeg
     * lavfi graph via `aresample=osr=88200:resampler=soxr:precision=33` (VHQ soxr).
     * No external Kotlin-side resampler stage is created for this path.
     *
     * This tier reports playback errors through [onError] because it is the last
     * resort — if it fails, the track cannot be played at all.
     *
     * @param path         Filesystem path for the source file.
     * @param sueEnabled   Whether the Sonic Upscaling Enhancer is currently enabled.
     * @param hiResEnabled Whether the Hi-Res Dynamic Remaster is currently enabled.
     * @param onError      Invoked when a hard failure occurs; receives a log message,
     *   a user-visible message, and an optional [Throwable] cause.
     * @return A fully populated [LoadedAudioSession], or `null` when [onError] was called.
     */
    internal fun tryLoadPcmFallbackSession(
        path: String,
        sueEnabled: Boolean,
        hiResEnabled: Boolean,
        onError: (logMessage: String, userMessage: String, throwable: Throwable?) -> Unit,
    ): LoadedAudioSession? {
        val decoder = FFmpegDecoder()
        val format = runCatching {
            decoder.open(path, forcePcm = true)
        }.getOrElse { t ->
            onError(
                "Decoder open failed: ${t.message}",
                t.message ?: "Decoder failed to open the stream",
                t,
            )
            decoder.close()
            return null
        }
        BitPerfectDiagnosticsLogger.onDecoderOpened(format, source = "T3")

        val isUsbDacPresent = usbDacPresenceDetector.isUsbDacPresent()
        val ratePlan = ratePolicy.resolvePcmSampleRatePlan(
            format = format,
            sueEnabled = sueEnabled,
            hiResEnabled = hiResEnabled,
            isUsbDacPresent = isUsbDacPresent,
        )
        val sueStage = buildSueStageIfNeeded(
            format = format,
            sueEnabled = sueEnabled,
            hiResEnabled = hiResEnabled,
            desiredOutputSampleRateHz = ratePlan.sueOutputRateHz,
            replayGainDb = resolveReplayGainDbIfNeeded(
                decoder = decoder,
                format = format,
                hiResEnabled = hiResEnabled,
            ),
            standaloneResampleTargetRateHz = ratePlan.standaloneResampleTargetRateHz,
        )
        val sinkFormat = pipelineOutputFormat(format, sueStage)
        val sink = runCatching {
            sinkRouter.createSinkForTrackFormat(sinkFormat, dsdPlaybackContext = null, sourcePath = path, sueStage = sueStage)
        }.getOrElse { t ->
            // Enhanced libusb sink failed to open (e.g. no UAC2 endpoint matches the
            // DSP output format on this DAC). Rather than aborting playback and
            // silently dropping the USB DAC, degrade to the plain bit-perfect libusb
            // passthrough sink for this track — the enhancement is skipped but the
            // signal keeps bypassing AudioFlinger through the same USB DAC, preserving
            // volume and routing continuity. Only falls through to a hard error when
            // no libusb passthrough is possible either.
            val sueWasActive = sueStage?.isActive == true
            sueStage?.close()
            if (sueWasActive) {
                Log.w(
                    PATH_TAG,
                    "Audiophile tier-3: enhanced sink failed (${t.message}) — retrying " +
                        "without DSP on the plain libusb passthrough sink",
                )
                val fallbackSink = runCatching {
                    sinkRouter.createSinkForTrackFormat(format, dsdPlaybackContext = null, sourcePath = path, sueStage = null)
                }.getOrNull()
                if (fallbackSink != null) {
                    Log.i(PATH_TAG, "Audiophile tier-3: DSP-less libusb passthrough fallback succeeded")
                    return LoadedAudioSession(
                        decoder = decoder,
                        format = format,
                        sink = fallbackSink,
                        dsdContext = null,
                        sueStage = null,
                        pathType = ratePlan.pathType,
                        decision = ratePlan.decision,
                        activeOutputThreadSampleRateHz = ratePlan.activeOutputThreadSampleRateHz,
                    )
                }
            }
            onError(
                "Audiophile output sink build failed: ${t.message}",
                "Audiophile output could not be opened: ${t.message}",
                t,
            )
            decoder.close()
            return null
        }

        Log.i(
            PATH_TAG,
            "Audiophile tier-3 PCM fallback: ${format.sampleRateHz}Hz/${format.sourceBitDepth}-bit" +
                if (format.isResampledDsd) " (DSD decimated to 88 200 Hz by lavfi+soxr VHQ inside decoder)" else "",
        )

        return LoadedAudioSession(
            decoder = decoder,
            format = format,
            sink = sink,
            dsdContext = null,
            sueStage = sueStage,
            pathType = ratePlan.pathType,
            decision = ratePlan.decision,
            activeOutputThreadSampleRateHz = ratePlan.activeOutputThreadSampleRateHz,
        )
    }
}

