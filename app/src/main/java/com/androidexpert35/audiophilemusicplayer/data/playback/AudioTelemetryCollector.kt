
package com.androidexpert35.audiophilemusicplayer.data.playback

import android.media.AudioFormat
import android.util.Log
import com.androidexpert35.audiophilemusicplayer.data.playback.engine.AudioEngineManager
import com.androidexpert35.audiophilemusicplayer.data.playback.engine.EnginePlaybackState
import com.androidexpert35.audiophilemusicplayer.data.playback.engine.EngineType
import com.androidexpert35.audiophilemusicplayer.data.playback.native_.AudioFormatInfo
import com.androidexpert35.audiophilemusicplayer.data.playback.native_.PipelinePathReport
import com.androidexpert35.audiophilemusicplayer.data.playback.native_.toSueStatus
import com.androidexpert35.audiophilemusicplayer.data.playback.usb.UsbVolumeController
import com.androidexpert35.audiophilemusicplayer.di.ApplicationScope
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioCodec
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioPathStatus
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioTelemetry
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.BitPerfectDiagnostics
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.DsdOutputMode
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.OutputStreamInfo
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.TelemetryStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Captures real-time audio telemetry from whichever [AudioPlayerEngine][com.androidexpert35.audiophilemusicplayer.data.playback.engine.AudioPlayerEngine] is
 * currently active, via [AudioEngineManager]'s mirrored `StateFlow`s.
 *
 * Bit-perfect status is sourced exclusively from [AudioPathValidator.pathState]:
 * `isBitPerfect` is `true` when and only when
 * [AudioPathState.pathStatus] == [AudioPathStatus.DIRECT_BIT_PERFECT].
 *
 * @property engine             Active-engine facade exposing mirrored telemetry flows.
 * @property audioPathValidator Authoritative path-status validator combining
 *   USB bit-perfect router events, engine path reports, and live device routing.
 * @property usbVolumeController Supplies the live software-volume unity state
 *   required for honest direct-USB PCM bit-perfect telemetry.
 * @property appScope           Long-lived scope used to drive the `combine` collector.
 */
@Singleton
class AudioTelemetryCollector @Inject constructor(
    private val engine: AudioEngineManager,
    private val audioPathValidator: AudioPathValidator,
    private val usbVolumeController: UsbVolumeController,
    @param:ApplicationScope private val appScope: CoroutineScope,
) {

    private val _telemetry = MutableStateFlow(AudioTelemetry.IDLE)

    /** Observable stream of real-time audio telemetry snapshots. */
    val telemetry: StateFlow<AudioTelemetry> = _telemetry.asStateFlow()

    private val _reloadRequested = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * Retained for API compatibility. In the current pipeline reload is not
     * needed — the engine already drives [telemetry] continuously.
     */
    val reloadRequested: SharedFlow<Unit> = _reloadRequested.asSharedFlow()

    init {
        val engineInputs = combine(
            engine.currentFormat,
            engine.pathReport,
            engine.state,
            engine.activeEngineType,
            audioPathValidator.pathState,
        ) { fmt, path, state, engineType, pathState ->
            TelemetryInputs(fmt, path, state, engineType, pathState)
        }

        combine(engineInputs, usbVolumeController.volumePct) { inputs, usbVolumePct ->
            buildSnapshot(
                format = inputs.format,
                report = inputs.report,
                state = inputs.state,
                engineType = inputs.engineType,
                pathState = inputs.pathState,
                usbVolumePct = usbVolumePct,
            )
        }
            .onEach { snapshot -> _telemetry.value = snapshot }
            .launchIn(appScope)

        observeTransportRefreshTriggers()
    }

    /**
     * Forces a best-effort telemetry rebuild on transport changes that users
     * expect to update the diagnostics surface immediately.
     *
     * The normal `combine` collector already keeps telemetry live continuously,
     * but play/pause and next/previous interactions can briefly leave the UI one
     * emission behind while the mirrored engine flows settle. Rebuilding from the
     * latest snapshots closes that gap so developer diagnostics and the player UI
     * reflect transport changes immediately.
     */
    private fun observeTransportRefreshTriggers() {
        engine.state
            .drop(1)
            .distinctUntilChanged()
            .onEach { state ->
                if (state == EnginePlaybackState.PLAYING || state == EnginePlaybackState.PAUSED) {
                    Log.d(TAG, "Transport-triggered telemetry refresh for state=$state")
                    requestReload()
                }
            }
            .launchIn(appScope)

        engine.currentUri
            .drop(1)
            .distinctUntilChanged()
            .onEach { uri ->
                Log.d(TAG, "Transport-triggered telemetry refresh for uri=${uri ?: "null"}")
                requestReload()
            }
            .launchIn(appScope)
    }

    private fun buildSnapshot(
        format: AudioFormatInfo?,
        report: PipelinePathReport?,
        state: EnginePlaybackState,
        engineType: EngineType,
        pathState: AudioPathState,
        usbVolumePct: Int,
    ): AudioTelemetry {
        if (format == null || state.isInactive) {
            return AudioTelemetry.IDLE.copy(
                isBitPerfect = TelemetryStatus.INACTIVE,
                bitPerfectDiagnostics = buildBitPerfectDiagnostics(
                    pathState, report, usbVolumePct
                ),
            )
        }

        val isAppOwnedProcessingActive = report?.sueInfo?.let { info ->
            info.isActive ||
                info.isHiResRemasterActive ||
                info.isForce48kResampleActive
        } == true

        // An enhanced libusb stream starts as float32 regardless of the decoder's
        // source depth, then the native boundary quantises it to the selected UAC2
        // endpoint's advertised resolution. Prefer that negotiated value so a
        // 16-bit lossy source processed into a 32-bit USB carrier is not incorrectly
        // reported as 16-bit. Unprocessed PCM keeps source-depth semantics: widening
        // S16 into a four-byte subslot does not create additional source information.
        val processedUsbBitDepth = report?.usbPcmValidBitDepth
            ?.takeIf { isAppOwnedProcessingActive && it in 1..32 }
        val bitDepth = processedUsbBitDepth ?: when (report?.encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> 32
            AudioFormat.ENCODING_PCM_32BIT -> format.sourceBitDepth.takeIf { it in 1..32 } ?: 32
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
            AudioFormat.ENCODING_PCM_16BIT -> 16
            else -> format.sourceBitDepth
        }

        val isDirectUsbPcmAttenuated =
            report?.usedDirectFlag == true &&
                report.audioSessionId == 0 &&
                report.routedDeviceType == USB_CLASS_AUDIO_SENTINEL &&
                report.dsdPipelineInfo == null &&
                usbVolumePct < USB_VOLUME_UNITY_PCT
        val isBitPerfect = when {
            isAppOwnedProcessingActive -> TelemetryStatus.INACTIVE
            isDirectUsbPcmAttenuated -> TelemetryStatus.INACTIVE
            else -> when (pathState.pathStatus) {
                // Keep this exhaustive so a future path tier forces an explicit
                // telemetry decision at compile time.
                AudioPathStatus.DIRECT_BIT_PERFECT -> TelemetryStatus.ACTIVE
                AudioPathStatus.DIRECT_SUPPORTED   -> TelemetryStatus.ACTIVE_UNCONFIRMED
                AudioPathStatus.OEM_WARNING        -> TelemetryStatus.INACTIVE
                AudioPathStatus.RESAMPLED          -> TelemetryStatus.INACTIVE
                AudioPathStatus.UNKNOWN            -> TelemetryStatus.UNAVAILABLE
            }
        }
        val diagnostics = buildBitPerfectDiagnostics(pathState, report, usbVolumePct)
        val streamInfo = buildStreamInfo(format, report, bitDepth)
        val isDirectPlayback = if(engineType == EngineType.AUDIOPHILE && report?.usedDirectFlag == true){
            TelemetryStatus.ACTIVE
        }else{
            TelemetryStatus.INACTIVE
        }

        val snapshot = AudioTelemetry(
            streamInfo = streamInfo,
            isOffloaded = TelemetryStatus.INACTIVE,
            isDirectPlayback = isDirectPlayback,
            isBitPerfect = isBitPerfect,
            bitPerfectDiagnostics = diagnostics,
            isAudiophileEngineActive = engineType == EngineType.AUDIOPHILE,
            sueStatus = report?.sueInfo?.toSueStatus(),
        )

        Log.d(
            TAG,
            "Snapshot: ${streamInfo.codec.displayName} " +
                "${(streamInfo as? OutputStreamInfo.Pcm)?.sampleRateHz ?: (streamInfo as? OutputStreamInfo.Dsd)?.pcmOutput?.sampleRateHz ?: 0}Hz " +
                "${(streamInfo as? OutputStreamInfo.Pcm)?.bitDepth ?: (streamInfo as? OutputStreamInfo.Dsd)?.pcmOutput?.bitDepth ?: 0}bit " +
                "direct=${snapshot.isDirectPlayback} bit-perfect=${snapshot.isBitPerfect} engine=$engineType"
        )
        Log.i(
            PATH_TAG,
            "AudioPath engine=$engineType pathStatus=${pathState.pathStatus} " +
                "pathType=${report?.pathType} decision=${report?.decision} " +
                "device=${pathState.activeDeviceName ?: "unknown"} " +
                "isBitPerfect=$isBitPerfect isDirectPlayback=$isDirectPlayback " +
                "isMixerBitPerfect=${pathState.isBitPerfectConfirmed} " +
                "isUsbBypass=${diagnostics.isDirectUsbBypass} " +
                "floatFallback=${report?.usedFloatFallback == true} " +
                "outputHz=${pathState.outputSampleRateHz} threadHz=${report?.activeOutputThreadSampleRateHz ?: 0} " +
                "nativeHz=${report?.nativeOutputSampleRateHz ?: 0}"
        )

        return snapshot
    }

    /**
     * Builds a [BitPerfectDiagnostics] snapshot from the current [AudioPathState]
     * and the raw engine path report.
     *
     * [BitPerfectDiagnostics] carries only what the advanced diagnostics UI needs —
     * the routing tier, device name, and individual signal-chain flags. All
     * multi-condition checklist logic has been removed; the single source of truth
     * is [AudioPathState.pathStatus].
     *
     * @param pathState Current audio path state from [AudioPathValidator].
     * @param report    Active engine path report, or `null` when idle.
     * @return Populated diagnostic snapshot for the telemetry dialog.
     */
    private fun buildBitPerfectDiagnostics(
        pathState: AudioPathState,
        report: PipelinePathReport?,
        usbVolumePct: Int,
    ): BitPerfectDiagnostics {
        val isDirectUsbBypass = report?.let {
            // ✅ CHANGED: raw literal `== 1` replaced with the shared
            //   USB_CLASS_AUDIO_SENTINEL constant from PlaybackRuntimeExt so
            //   both AudioPathValidator and this collector stay in sync if
            //   the sentinel ever needs to change.
            it.usedDirectFlag &&
                it.audioSessionId == 0 &&
                it.routedDeviceType == USB_CLASS_AUDIO_SENTINEL
        } == true

        return BitPerfectDiagnostics(
            pathStatus = pathState.pathStatus,
            activeDeviceName = pathState.activeDeviceName,
            outputRouteKind = mapOutputRouteKind(
                deviceType = pathState.activeDeviceType,
                isDirectUsbBypass = isDirectUsbBypass,
            ),
            isDirectPlayback = report?.usedDirectFlag == true,
            isDirectUsbBypass = isDirectUsbBypass,
            isMixerBitPerfect = pathState.isBitPerfectConfirmed,
            noFloatFallback = report?.usedFloatFallback != true,
            isSoftwareVolumeAtUnity =
                !isDirectUsbBypass ||
                    report.dsdPipelineInfo != null ||
                    usbVolumePct >= USB_VOLUME_UNITY_PCT,
        )
    }

    /**
     * Assembles the concrete [OutputStreamInfo] subtype for the active stream.
     */
    private fun buildStreamInfo(
        format: AudioFormatInfo,
        report: PipelinePathReport?,
        bitDepth: Int,
    ): OutputStreamInfo {
        val codec = format.codec.takeIf { it != AudioCodec.UNKNOWN } ?: AudioCodec.UNKNOWN
        val dsdRate = report?.dsdPipelineInfo?.sourceDsdRate ?: format.dsdRate

        if ((format.isDsd || format.isResampledDsd) && dsdRate != null) {
            val outputMode = report?.dsdPipelineInfo?.outputMode ?: DsdOutputMode.Unsupported
            val pcmOutput = when {
                format.isResampledDsd -> OutputStreamInfo.Dsd.PcmOutput(
                    sampleRateHz = report?.sampleRateHz?.takeIf { it > 0 } ?: format.sampleRateHz,
                    bitDepth = bitDepth,
                )
                outputMode is DsdOutputMode.DoP -> {
                    val carrierHz = report?.dsdPipelineInfo?.dopPcmRate
                        ?: report?.sampleRateHz?.takeIf { it > 0 }
                        ?: 0
                    if (carrierHz > 0) OutputStreamInfo.Dsd.PcmOutput(
                        sampleRateHz = carrierHz,
                        bitDepth = bitDepth,
                    ) else null
                }
                else -> null
            }
            return OutputStreamInfo.Dsd(
                codec = codec,
                sourceContainer = report?.dsdPipelineInfo?.sourceFormat ?: format.dsdSourceFormat,
                sourceDsdRate = dsdRate,
                outputMode = outputMode,
                pcmOutput = pcmOutput,
            )
        }

        return OutputStreamInfo.Pcm(
            codec = codec,
            sampleRateHz = report?.sampleRateHz?.takeIf { it > 0 } ?: format.sampleRateHz,
            bitDepth = bitDepth,
            bitrateKbps = format.bitrateKbps,
        )
    }


    /**
     * Forces an immediate telemetry snapshot rebuild from the current engine
     * [StateFlow] values without waiting for a new flow emission from the combine.
     *
     * This is the canonical path for lifecycle-driven refreshes such as
     * [com.androidexpert35.audiophilemusicplayer.presentation.activity.MainActivity.onResume]
     * and [com.androidexpert35.audiophilemusicplayer.presentation.activity.MainActivity.onStart].
     * It covers two practical scenarios where the continuous [combine] collector
     * would otherwise produce stale / idle telemetry:
     *
     * 1. **Cold-start session restore** — the playback engine loads the track
     *    asynchronously on its audio thread; by the time the activity reaches
     *    `onResume` the engine's [StateFlow]s may already hold the correct
     *    format and path-report values but the [combine] has not yet emitted a
     *    new snapshot to this ViewModel. A direct rebuild closes that window.
     *
     * 2. **Post-pause sink release** — the bit-perfect engine releases its
     *    [com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile.AudiophileOutputSink]
     *    immediately at the pause boundary. The path report is intentionally retained (the routing
     *    configuration is still valid while paused), but no new flow emission is
     *    triggered because the StateFlow values did not change. Calling this on
     *    `onResume` refreshes the UI immediately when the user returns to a long-
     *    paused session.
     *
     * Also emits to [reloadRequested] for any legacy consumers that may still
     * observe that flow.
     */
    fun requestReload() {
        _reloadRequested.tryEmit(Unit)
        // Re-build synchronously from the current StateFlow snapshots so the UI
        // reflects the correct telemetry the moment the activity gains focus —
        // without waiting for the combine collector to fire again.
        val snapshot = buildSnapshot(
            format    = engine.currentFormat.value,
            report    = engine.pathReport.value,
            state     = engine.state.value,
            engineType = engine.activeEngineType.value,
            pathState = audioPathValidator.pathState.value,
            usbVolumePct = usbVolumeController.volumePct.value,
        )
        _telemetry.value = snapshot
    }

    /** Resets the telemetry snapshot to [AudioTelemetry.IDLE]. */
    fun reset() {
        _telemetry.value = AudioTelemetry.IDLE
    }

    private companion object {
        const val TAG = "AudioTelemetry"
        const val PATH_TAG = AUDIPHILE_PATH_TAG
        const val USB_VOLUME_UNITY_PCT = 100
    }
}

private data class TelemetryInputs(
    val format: AudioFormatInfo?,
    val report: PipelinePathReport?,
    val state: EnginePlaybackState,
    val engineType: EngineType,
    val pathState: AudioPathState,
)
