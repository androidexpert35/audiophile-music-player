// ✅ EXTRACTED FROM: BitPerfectPlaybackEngine.kt
package com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile

import android.util.Log
import com.androidexpert35.audiophilemusicplayer.data.playback.AUDIPHILE_PATH_TAG
import com.androidexpert35.audiophilemusicplayer.data.playback.dsd.DoPEncoder
import com.androidexpert35.audiophilemusicplayer.data.playback.dsd.DsdCapabilityDetector
import com.androidexpert35.audiophilemusicplayer.data.playback.native_.AudioFormatInfo
import com.androidexpert35.audiophilemusicplayer.data.playback.usb.UsbAudioSinkFactory
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.DsdOutputMode
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.DsdRate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Selects and creates the correct output sink and DSD transport context for a
 * given audio format, encapsulating all routing decisions that depend on USB
 * hardware state.
 *
 * Centralising sink selection here keeps [BitPerfectPlaybackEngine] free from
 * direct USB factory and DSD capability knowledge. All methods are called on
 * the audio thread.
 *
 * @property usbAudioSinkFactory Factory that vends platform AudioTrack and
 *   direct-USB sink implementations.
 * @property dsdCapabilityDetector Detects native DSD and DoP capabilities for
 *   the currently connected output device.
 */
@Singleton
internal class BitPerfectSinkRouter @Inject constructor(
    private val usbAudioSinkFactory: UsbAudioSinkFactory,
    private val dsdCapabilityDetector: DsdCapabilityDetector,
) {

    private companion object {
        /** Divisor converting a DSD rate's base sample-rate to its DoP PCM carrier rate. */
        const val DOP_RATE_DIVISOR = 16
        const val PATH_TAG = AUDIPHILE_PATH_TAG
    }

    /**
     * Returns `true` when a libusb-ready USB DAC is currently selected.
     *
     * This state participates in sample-rate planning and sink selection. A track
     * without app-owned DSP uses the native decoder pump; an active SUE or Hi-Res
     * stage keeps the Kotlin processing loop and writes its result to the same
     * libusb ISO transport through the enhanced sink.
     */
    fun isLibusbOutputActive(): Boolean =
        usbAudioSinkFactory.currentUsbDeviceState().isLibusbReady

    /**
     * Resolves the bit-perfect DSD transport context (native DSD or DoP) for
     * [format], or `null` when no bit-perfect path is available.
     *
     * A `null` return is NOT an error — it signals the caller to fall through
     * to the PCM fallback tier. Error reporting is delegated to the final tier
     * so intermediate attempts can silently degrade.
     *
     * @param format Decoded DSD source format.
     * @return A [DsdPlaybackContext] describing the negotiated transport, or `null`.
     */
    fun buildDsdPlaybackContext(format: AudioFormatInfo): DsdPlaybackContext? {
        val sourceRate = format.dsdRate ?: return null

        val outputMode = dsdCapabilityDetector.resolvePlaybackMode(
            format = format,
            usbState = usbAudioSinkFactory.currentUsbDeviceState(),
        )
        Log.i(
            PATH_TAG,
            "buildDsdPlaybackContext: sourceRate=${sourceRate.displayName} outputMode=$outputMode"
        )
        return when (outputMode) {
            is DsdOutputMode.NativeDsd -> DsdPlaybackContext(
                sourceRate = sourceRate,
                effectiveRate = sourceRate,
                outputMode = outputMode,
                sourceFormat = format.dsdSourceFormat ?: "DSD",
                dopPcmRate = null,
                dopEncoder = null,
            )

            is DsdOutputMode.DoP -> {
                val effectiveRate = dsdCapabilityDetector.resolveEffectiveDoPRate(
                    sourceRate = sourceRate,
                    maxPcmRate = outputMode.maxPcmRate,
                ) ?: return null
                if (sourceRate == DsdRate.DSD256 && effectiveRate == DsdRate.DSD128) {
                    Log.w(PATH_TAG, "DSD256 DoP requires 705.6 kHz PCM; downsampling to DSD128 before DoP transport")
                }
                DsdPlaybackContext(
                    sourceRate = sourceRate,
                    effectiveRate = effectiveRate,
                    outputMode = outputMode,
                    sourceFormat = format.dsdSourceFormat ?: "DSD",
                    dopPcmRate = effectiveRate.sampleRateHz / DOP_RATE_DIVISOR,
                    dopEncoder = DoPEncoder(effectiveRate),
                )
            }

            DsdOutputMode.Unsupported -> {
                Log.w(PATH_TAG, "buildDsdPlaybackContext: DsdOutputMode.Unsupported → null")
                null
            }
        }
    }

    /**
     * Builds a DoP-only [DsdPlaybackContext] for [format], independent of any
     * native-DSD capability the active route may advertise.
     *
     * Used as the secondary candidate in [buildBitPerfectDsdCandidates] for DAPs
     * that advertise [android.media.AudioFormat.ENCODING_DSD] via an internal
     * path we cannot open, yet still accept a bit-perfect DoP PCM carrier.
     *
     * @param format Decoded DSD source format.
     * @return A DoP-mode [DsdPlaybackContext], or `null` when no viable carrier
     *   rate exists for the active output.
     */
    fun buildDopOnlyPlaybackContext(format: AudioFormatInfo): DsdPlaybackContext? {
        val sourceRate = format.dsdRate ?: return null
        val mode = dsdCapabilityDetector.resolveDoPOnlyPlaybackMode(
            format = format,
            usbState = usbAudioSinkFactory.currentUsbDeviceState(),
        ) as? DsdOutputMode.DoP ?: return null

        val effectiveRate = dsdCapabilityDetector.resolveEffectiveDoPRate(
            sourceRate = sourceRate,
            maxPcmRate = mode.maxPcmRate,
        ) ?: return null

        return DsdPlaybackContext(
            sourceRate = sourceRate,
            effectiveRate = effectiveRate,
            outputMode = mode,
            sourceFormat = format.dsdSourceFormat ?: "DSD",
            dopPcmRate = effectiveRate.sampleRateHz / DOP_RATE_DIVISOR,
            dopEncoder = DoPEncoder(effectiveRate),
        )
    }

    /**
     * Returns the ordered list of bit-perfect DSD transport contexts to try for
     * [format] before degrading to the PCM fallback tier.
     *
     * Order:
     *   1. Primary mode chosen by [DsdCapabilityDetector.resolvePlaybackMode]
     *      (native DSD when supported, otherwise DoP).
     *   2. DoP-only fallback — important for DAPs that advertise
     *      [android.media.AudioFormat.ENCODING_DSD] internally but only accept
     *      a DoP PCM carrier through [android.media.AudioTrack].
     *
     * Duplicates are filtered so the engine never attempts the same context twice.
     *
     * @param format Decoded DSD source format.
     * @return Ordered candidate list; empty when no bit-perfect path is viable.
     */
    fun buildBitPerfectDsdCandidates(format: AudioFormatInfo): List<DsdPlaybackContext> {
        val primary = buildDsdPlaybackContext(format)
        val dopOnly = buildDopOnlyPlaybackContext(format)
        Log.i(
            PATH_TAG,
            "buildBitPerfectDsdCandidates: dsdRate=${format.dsdRate?.displayName} " +
                "primary=${primary?.outputMode?.let { it::class.simpleName }} " +
                "dopOnly=${dopOnly?.outputMode?.let { it::class.simpleName }}"
        )
        val candidates = listOfNotNull(primary, dopOnly)
        // Deduplicate on the effective output mode so we don't repeat work when
        // the primary was already DoP.
        return candidates.distinctBy { it.outputMode::class to it.effectiveRate }
    }

    /**
     * Creates the appropriate [AudiophileOutputSink] for a given format and DSD
     * transport context.
     *
     * PCM routing priority (when [dsdPlaybackContext] is `null`):
     * - **libusb + DSP active** → `LibusbPcmEnhancedSink` (Kotlin write-loop, float32)
     * - **libusb + no DSP** → `LibusbPcmAudioSink` (native pump, bit-perfect)
     * - **No libusb / no sourcePath** → platform AudioTrack via [UsbAudioSinkFactory.create]
     *
     * DSD routing:
     * - When libusb is ready, both NativeDSD and DoP go through
     *   `LibusbDsdAudioSink` which auto-switches NativeDSD → DoP internally.
     * - Without libusb, NativeDSD falls back to the legacy `UsbAudioSink` path
     *   and DoP uses the platform AudioTrack with direct-output flag.
     *
     * @param format             Format to pass to the sink factory. For PCM paths
     *   with an active [SueStage] this should be [pipelineOutputFormat], i.e.
     *   float32 at the SUE output rate.
     * @param dsdPlaybackContext Active DSD transport context, or `null` for PCM.
     * @param sourcePath         Filesystem or `/proc/self/fd/<n>` path for the
     *   libusb native pump; `null` forces a platform fallback.
     * @param sueStage           Active enhancement stage; determines libusb PCM
     *   routing sub-path.
     * @return A ready-to-play [AudiophileOutputSink].
     * @throws Exception when every considered sink route fails to open.
     */
    fun createSinkForTrackFormat(
        format: AudioFormatInfo,
        dsdPlaybackContext: DsdPlaybackContext?,
        sourcePath: String? = null,
        sueStage: SueStage? = null,
    ): AudiophileOutputSink = when (val ctx = dsdPlaybackContext) {
        null -> {
            val state = usbAudioSinkFactory.currentUsbDeviceState()
            Log.i(
                PATH_TAG,
                "createSinkForTrackFormat[PCM]: ${format.sampleRateHz}Hz/${format.sourceBitDepth}b " +
                    "isLibusbReady=${state.isLibusbReady} sourcePath=${sourcePath != null} " +
                    "sueActive=${sueStage?.isActive == true}"
            )
            if (sourcePath != null && state.isLibusbReady) {
                if (sueStage?.isActive == true) {
                    // DSP active: Kotlin write loop feeds ring via nativeWriteToRingBuffer.
                    Log.i(
                        PATH_TAG,
                        "createSinkForTrackFormat[PCM]: routing to LibusbPcmEnhancedSink " +
                            "(DSP active — Kotlin write loop, outputRate=${format.sampleRateHz}Hz)"
                    )
                    usbAudioSinkFactory.createLibusbPcmEnhancedSink(format)
                } else {
                    // No DSP: native pump delivers decoder output directly to ring.
                    Log.i(PATH_TAG, "createSinkForTrackFormat[PCM]: routing to LibusbPcmAudioSink (passthrough)")
                    usbAudioSinkFactory.createLibusbPcmSink(format, sourcePath)
                }
            } else {
                Log.i(
                    PATH_TAG,
                    "createSinkForTrackFormat[PCM]: routing to platform AudioTrackSink " +
                        "(isLibusbReady=${state.isLibusbReady} hasSourcePath=${sourcePath != null})"
                )
                usbAudioSinkFactory.create(format)
            }
        }
        else -> {
            val state = usbAudioSinkFactory.currentUsbDeviceState()
            Log.i(
                PATH_TAG,
                "createSinkForTrackFormat[DSD]: outputMode=${ctx.outputMode::class.simpleName} " +
                    "effectiveRate=${ctx.effectiveRate.displayName} sourceRate=${ctx.sourceRate.displayName} " +
                    "isLibusbReady=${state.isLibusbReady} sourcePath=${sourcePath != null}"
            )
            when (ctx.outputMode) {
                is DsdOutputMode.NativeDsd -> {
                    if (sourcePath != null && state.isLibusbReady) {
                        // Always use the libusb isochronous engine for USB DSD.
                        // The C++ DsdPlaybackManager monitors ISO transfer STATUS bits and
                        // auto-switches from native DSD to DoP within 200 ms if the DAC
                        // returns STALL on native DSD frames.
                        Log.i(
                            PATH_TAG,
                            "createSinkForTrackFormat[NativeDSD]: creating LibusbDsdAudioSink " +
                                "dsdRate=${ctx.effectiveRate.displayName}"
                        )
                        usbAudioSinkFactory.createLibusbDsdSink(ctx.effectiveRate, sourcePath)
                        // No ?: fallback to createNativeDsdSink here. If createLibusbDsdSink
                        // throws, let it propagate so the caller (tryLoadBitPerfectSession)
                        // can try the DoP candidate next.
                    } else {
                        Log.i(
                            PATH_TAG,
                            "createSinkForTrackFormat[NativeDSD]: non-libusb path — " +
                                "creating legacy UsbAudioSink isLibusbReady=${state.isLibusbReady}"
                        )
                        usbAudioSinkFactory.createNativeDsdSink(ctx.effectiveRate)
                    }
                }

                is DsdOutputMode.DoP -> {
                    if (sourcePath != null && state.isLibusbReady) {
                        // For DoP over libusb: createLibusbDsdSink starts the C++ DsdPlaybackManager
                        // in native-DSD mode. The manager detects STALL and auto-switches to DoP —
                        // so this sink correctly handles both DoP-only and native-then-DoP DACs.
                        Log.i(
                            PATH_TAG,
                            "createSinkForTrackFormat[DoP]: creating LibusbDsdAudioSink for DoP " +
                                "dsdRate=${ctx.effectiveRate.displayName} dopPcmRate=${ctx.dopPcmRate}"
                        )
                        usbAudioSinkFactory.createLibusbDsdSink(ctx.effectiveRate, sourcePath)
                    } else {
                        Log.i(
                            PATH_TAG,
                            "createSinkForTrackFormat[DoP]: non-libusb path — platform DoP " +
                                "dopPcmRate=${ctx.dopPcmRate} isLibusbReady=${state.isLibusbReady}"
                        )
                        usbAudioSinkFactory.create(
                            format = createDoPTransportFormat(format, ctx.dopPcmRate ?: 0),
                            requireDirectOutput = true,
                        )
                    }
                }

                DsdOutputMode.Unsupported ->
                    error("Unsupported DSD mode cannot create an output sink")
            }
        }
    }
}
