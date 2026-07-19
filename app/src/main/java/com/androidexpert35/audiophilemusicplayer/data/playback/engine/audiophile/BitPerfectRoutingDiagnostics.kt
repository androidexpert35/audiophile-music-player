package com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile

import android.util.Log
import com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile.BitPerfectRoutingDiagnostics.ROUTING_TAG
import com.androidexpert35.audiophilemusicplayer.data.playback.native_.AudioFormatInfo

/**
 * Stateless routing diagnostics for the Audiophile bit-perfect engine.
 *
 * Emits structured log lines to the [ROUTING_TAG] logcat tag so audio-path
 * decisions can be monitored independently of the main engine log stream:
 *
 * ```
 * adb logcat -s AudiophileRouting
 * ```
 *
 * All methods are pure — they read only the arguments passed to them and
 * hold no mutable state. This object is safe to call from any thread.
 */
internal object BitPerfectRoutingDiagnostics {

    private const val ROUTING_TAG = "AudiophileRouting"

    /**
     * Emits a vivid multi-line routing banner to logcat under [ROUTING_TAG].
     *
     * Fires once at track-load time and once on manual resume so the active
     * audio path is always visible in logcat when audio begins flowing.
     *
     * @param activeSink The sink currently delivering audio frames.
     * @param format     Decoded source format produced by the FFmpeg pipeline.
     * @param trigger    Short label describing what caused this log ("load", "resume").
     */
    fun logRoutingBanner(
        activeSink: AudiophileOutputSink,
        format: AudioFormatInfo,
        trigger: String,
    ) {
        val report = activeSink.pathReport
        val dsdInfo = report.dsdPipelineInfo

        val (routingLabel, pathDetail) = when (activeSink) {
            is com.androidexpert35.audiophilemusicplayer.data.playback.usb.LibusbDsdAudioSink -> {
                val modeLabel = dsdInfo?.outputMode?.let { it::class.simpleName } ?: "NativeDSD"
                val rateLabel = dsdInfo?.effectiveDsdRate?.displayName ?: format.codec.displayName
                "*** DIRECT LIBUSB USB  [DSD] ***" to
                    "mode=$modeLabel  rate=$rateLabel"
            }
            is com.androidexpert35.audiophilemusicplayer.data.playback.usb.LibusbPcmEnhancedSink -> {
                // Kotlin write loop path: SUE / Hi-Res processing applied before ring write.
                "*** DIRECT LIBUSB USB  [PCM + DSP] ***" to
                    "${format.sampleRateHz} Hz / ${format.sourceBitDepth}-bit / ${format.channelCount}ch (Kotlin write loop)"
            }
            is com.androidexpert35.audiophilemusicplayer.data.playback.usb.LibusbPcmAudioSink ->
                "*** DIRECT LIBUSB USB  [PCM] ***" to
                    "${format.sampleRateHz} Hz / ${format.sourceBitDepth}-bit / ${format.channelCount}ch"
            else -> {
                // Android AudioTrack path — may still reach the USB DAC via the OS mixer.
                val mixLabel = when {
                    report.usedDirectFlag && !report.usedFloatFallback -> "FLAG_DIRECT (bit-perfect candidate)"
                    report.usedDirectFlag && report.usedFloatFallback  -> "FLAG_DIRECT + float fallback"
                    else                                                -> "MIXED  (AudioFlinger resampler)"
                }
                "--- ANDROID AUDIOTRACK  [$mixLabel] ---" to
                    "${report.sampleRateHz} Hz / encoding=${report.encoding} / " +
                    "nativeOut=${report.nativeOutputSampleRateHz} Hz"
            }
        }

        Log.i(ROUTING_TAG, "=== AUDIPHILE ROUTING ($trigger) ===")
        Log.i(ROUTING_TAG, "  $routingLabel")
        Log.i(ROUTING_TAG, "  PATH   : $pathDetail")
        Log.i(ROUTING_TAG, "  DEVICE : ${report.routedDeviceName ?: "unknown"}")
        Log.i(ROUTING_TAG, "  SINK   : ${activeSink::class.simpleName}")
        if (dsdInfo != null) {
            Log.i(ROUTING_TAG, "  DSD SRC: ${dsdInfo.sourceFormat}  -> output=${dsdInfo.outputMode::class.simpleName}")
        }
        report.sueInfo?.takeIf { it.isActive }?.let { sue ->
            Log.i(ROUTING_TAG, "  SUE    : active  codec=${sue.codecDisplayName}  profile=${sue.intensityProfile}")
        }
        Log.i(ROUTING_TAG, "================================")
    }

    /**
     * Returns a compact single-line routing description for the heartbeat log.
     *
     * Callers typically prefix this with `"[heartbeat]"` and append `pos=<ms>`.
     *
     * @param activeSink Active output sink.
     * @param format     Decoded source format.
     * @return One-line routing summary string, safe to emit directly to logcat.
     */
    fun describeRoutingShort(activeSink: AudiophileOutputSink, format: AudioFormatInfo): String {
        val report = activeSink.pathReport
        return when (activeSink) {
            is com.androidexpert35.audiophilemusicplayer.data.playback.usb.LibusbDsdAudioSink ->
                "LIBUSB-DSD ${report.dsdPipelineInfo?.effectiveDsdRate?.displayName ?: ""}" +
                    " mode=${report.dsdPipelineInfo?.outputMode?.let { it::class.simpleName } ?: "NativeDSD"}" +
                    " device=${report.routedDeviceName ?: "?"}"
            is com.androidexpert35.audiophilemusicplayer.data.playback.usb.LibusbPcmEnhancedSink ->
                "LIBUSB-PCM-DSP ${format.sampleRateHz}Hz/${format.sourceBitDepth}bit" +
                    " device=${report.routedDeviceName ?: "?"}"
            is com.androidexpert35.audiophilemusicplayer.data.playback.usb.LibusbPcmAudioSink ->
                "LIBUSB-PCM ${format.sampleRateHz}Hz/${format.sourceBitDepth}bit" +
                    " device=${report.routedDeviceName ?: "?"}"
            else -> {
                val path = if (report.usedDirectFlag) "AT-DIRECT" else "AT-MIXED"
                "$path ${report.sampleRateHz}Hz enc=${report.encoding}" +
                    " device=${report.routedDeviceName ?: "?"}"
            }
        }
    }
}

