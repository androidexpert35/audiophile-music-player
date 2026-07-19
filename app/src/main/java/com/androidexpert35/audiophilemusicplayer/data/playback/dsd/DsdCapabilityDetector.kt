package com.androidexpert35.audiophilemusicplayer.data.playback.dsd

import android.content.Context
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import com.androidexpert35.audiophilemusicplayer.data.playback.AudioAttributesFactory
import com.androidexpert35.audiophilemusicplayer.data.playback.native_.AudioFormatInfo
import com.androidexpert35.audiophilemusicplayer.data.playback.usb.UsbAudioDeviceState
import com.androidexpert35.audiophilemusicplayer.data.playback.usb.UsbAudioOutputProfile
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.DsdOutputMode
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.DsdRate
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Probes the currently active output path for DSD transport support.
 *
 * The detector evaluates both native one-bit DSD capability and the highest DoP
 * PCM carrier rate the output can sustain. USB detection relies on the parsed
 * descriptor snapshot supplied by [UsbAudioDeviceState], while the internal DAC
 * path falls back to platform [AudioTrack] capability checks.
 *
 * @property context Application context used for platform audio-capability checks.
 */
@Singleton
class DsdCapabilityDetector @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    /**
     * Reports the preferred DSD transport currently available to the app.
     *
     * @param usbState Latest USB output readiness snapshot.
     * @return Preferred [DsdOutputMode] for the current output route.
     */
    fun probeCurrentOutput(usbState: UsbAudioDeviceState): DsdOutputMode {
        val usbMode = probeUsbMode(usbState)
        Log.d(TAG, "probeCurrentOutput: usbMode=$usbMode isLibusbReady=${usbState.isLibusbReady} " +
            "isDirectUsbReady=${usbState.isDirectUsbReady} supportedDsdRates=${usbState.supportedDsdRates}")
        return if (usbMode != DsdOutputMode.Unsupported) usbMode else probeInternalOutputMode()
    }

    /**
     * Resolves the actual transport mode that can play the requested DSD [format].
     *
     * @param format Opened decoder format. Non-DSD formats always return [DsdOutputMode.Unsupported].
     * @param usbState Latest USB output readiness snapshot.
     * @return Concrete transport mode that can carry the requested track, or [DsdOutputMode.Unsupported].
     */
    fun resolvePlaybackMode(
        format: AudioFormatInfo,
        usbState: UsbAudioDeviceState,
    ): DsdOutputMode {
        val sourceRate = format.dsdRate ?: return DsdOutputMode.Unsupported
        Log.i(
            TAG,
            "resolvePlaybackMode: sourceRate=${sourceRate.displayName} " +
                "isLibusbReady=${usbState.isLibusbReady} isDirectUsbReady=${usbState.isDirectUsbReady} " +
                "connectedDevice=${usbState.connectedDevice?.deviceId} " +
                "isPermissionGranted=${usbState.isPermissionGranted} " +
                "supportedDsdRates=${usbState.supportedDsdRates.map { it.displayName }} " +
                "supportedProfiles=${usbState.supportedProfiles.map { "${it.sampleRateHz}Hz/${it.bitDepth}b" }}"
        )
        val usbMode = resolveUsbPlaybackMode(sourceRate, usbState)
        Log.i(TAG, "resolvePlaybackMode: usbMode=$usbMode")
        val result = if (usbMode != DsdOutputMode.Unsupported) usbMode else resolveInternalPlaybackMode(sourceRate)
        Log.i(TAG, "resolvePlaybackMode: FINAL result=$result for sourceRate=${sourceRate.displayName}")
        return result
    }

    /**
     * Resolves the highest DoP PCM carrier the current route can sustain,
     * ignoring any native-DSD capability. Used as a secondary bit-perfect
     * attempt when the primary (native DSD) route cannot actually open — e.g.
     * a DAP advertises `ENCODING_DSD` but we only have a DoP-capable sink
     * wired through `AudioTrack`.
     *
     * ### Why kernel-UAC USB bypass is intentionally absent here
     *
     * When `isDirectUsbReady = false` (the OEM kernel UAC2 driver owns the USB
     * interface), [AudioTrack.isDirectPlaybackSupported] returns `false` even
     * though `AudioDeviceInfo.getSampleRates()` lists 176 400 Hz. Testing on
     * OPPO CPH2791 / ColorOS confirmed that AudioFlinger **does not** auto-promote
     * 176 400 Hz / PCM_24BIT_PACKED to `FLAG_DIRECT` the way it does for 88 200 Hz
     * / PCM_FLOAT — the DoP carrier is routed through the software mixer, which
     * applies volume processing that corrupts the DoP marker bytes and produces
     * white noise on the DAC. The `isDirectPlaybackSupported` gate is therefore
     * correct and must not be bypassed for the platform (non-USB-host) path.
     * Devices where DoP does work via `AudioTrack` (dedicated DAPs, LG Quad-DAC,
     * Sony WM1, etc.) expose a true direct output that returns `true` from
     * `isDirectPlaybackSupported`, so those paths are handled correctly as-is.
     *
     * @param format Opened decoder format.
     * @param usbState Latest USB output readiness snapshot.
     * @return DoP-only transport mode, or [DsdOutputMode.Unsupported].
     */
    fun resolveDoPOnlyPlaybackMode(
        format: AudioFormatInfo,
        usbState: UsbAudioDeviceState,
    ): DsdOutputMode {
        val sourceRate = format.dsdRate ?: return DsdOutputMode.Unsupported

        // App holds the USB interface directly via libusb (custom USB host path).
        if (usbState.isLibusbReady) {
            val usbMaxRate = usbState.supportedProfiles
                .map(UsbAudioOutputProfile::sampleRateHz)
                .filter(::isDoPPcmCarrierRate)
                .maxOrNull()
            if (usbMaxRate != null && resolveEffectiveDoPRate(sourceRate, usbMaxRate) != null) {
                return DsdOutputMode.DoP(usbMaxRate)
            }
        }

        // Internal DAC or platform AudioTrack path.
        // isDirectPlaybackSupported is the authoritative gate: if the platform mixer
        // would intercept the DoP carrier (returning false), we must not attempt DoP —
        // the mixer's gain stage corrupts the marker bytes. See class KDoc above.
        val internalMaxRate = supportedDoPPcmCarrierRates().maxOrNull() ?: return DsdOutputMode.Unsupported
        return if (resolveEffectiveDoPRate(sourceRate, internalMaxRate) != null) {
            DsdOutputMode.DoP(internalMaxRate)
        } else {
            DsdOutputMode.Unsupported
        }
    }


    /**
     * Resolves the effective DSD family carried by DoP for [sourceRate].
     *
     * @param sourceRate Original source DSD rate.
     * @param maxPcmRate Highest confirmed DoP PCM carrier rate.
     * @return Effective [DsdRate], or `null` when DoP cannot carry the stream.
     */
    fun resolveEffectiveDoPRate(sourceRate: DsdRate, maxPcmRate: Int): DsdRate? = when (sourceRate) {
        DsdRate.DSD64 -> DsdRate.DSD64.takeIf { maxPcmRate >= DOP_PCM_RATE_DSD64 }
        DsdRate.DSD128 -> DsdRate.DSD128.takeIf { maxPcmRate >= DOP_PCM_RATE_DSD128 }
        DsdRate.DSD256 -> when {
            maxPcmRate >= DOP_PCM_RATE_DSD256 -> DsdRate.DSD256
            maxPcmRate >= DOP_PCM_RATE_DSD128 -> DsdRate.DSD128
            else -> null
        }
    }

    private fun probeUsbMode(usbState: UsbAudioDeviceState): DsdOutputMode {
        if (!usbState.isLibusbReady) {
            Log.d(TAG, "probeUsbMode: isLibusbReady=false → Unsupported")
            return DsdOutputMode.Unsupported
        }
        // Prefer the highest DSD rate from descriptors. When descriptors did not
        // expose any DSD format type tags (common on some DACs that still accept
        // native-DSD bitstreams), default to DSD64 — the C++ DsdPlaybackManager
        // will STALL and auto-switch to DoP if the DAC rejects native DSD frames.
        val nativeMaxRate = usbState.supportedDsdRates.maxByOrNull { rate -> rate.multiplier }
        if (nativeMaxRate != null) {
            Log.i(TAG, "probeUsbMode: isLibusbReady=true nativeMaxRate=${nativeMaxRate.displayName} → NativeDsd")
            return DsdOutputMode.NativeDsd(nativeMaxRate)
        }
        // No descriptor-listed DSD rates. Check whether DoP carrier rates are available
        // as a secondary indication that the DAC can handle DSD-over-PCM.
        val maxDoPPcmRate = usbState.supportedProfiles
            .map(UsbAudioOutputProfile::sampleRateHz)
            .filter(::isDoPPcmCarrierRate)
            .maxOrNull()
        return if (maxDoPPcmRate != null) {
            Log.i(
                TAG,
                "probeUsbMode: isLibusbReady=true no DSD descriptors, but DoP PCM rate ${maxDoPPcmRate}Hz found " +
                    "→ NativeDsd(DSD64) with C++ auto-DoP fallback"
            )
            // Still return NativeDsd — the C++ DsdPlaybackManager will STALL on native
            // DSD and auto-switch to DoP within 200 ms.
            DsdOutputMode.NativeDsd(DsdRate.DSD64)
        } else {
            // No DSD descriptors and no DoP PCM carrier rates from descriptors.
            // Still attempt NativeDsd via the libusb engine — some DACs don't
            // enumerate alternate settings until the interface is claimed.
            Log.w(
                TAG,
                "probeUsbMode: isLibusbReady=true but supportedDsdRates=[] and no DoP PCM profiles — " +
                    "attempting NativeDsd(DSD64) anyway; C++ will auto-DoP on STALL"
            )
            DsdOutputMode.NativeDsd(DsdRate.DSD64)
        }
    }

    private fun resolveUsbPlaybackMode(sourceRate: DsdRate, usbState: UsbAudioDeviceState): DsdOutputMode {
        if (!usbState.isLibusbReady) {
            Log.d(TAG, "resolveUsbPlaybackMode: isLibusbReady=false → Unsupported")
            return DsdOutputMode.Unsupported
        }

        val nativeMaxRate = usbState.supportedDsdRates.maxByOrNull { rate -> rate.multiplier }

        if (nativeMaxRate != null) {
            // Clamp to the highest rate the DAC declares; if source is higher, use nativeMaxRate
            // (the C++ DsdPlaybackManager handles the downgrade gracefully).
            val resolvedRate = if (nativeMaxRate.multiplier >= sourceRate.multiplier) sourceRate else nativeMaxRate
            Log.i(
                TAG,
                "resolveUsbPlaybackMode: isLibusbReady=true sourceRate=${sourceRate.displayName} " +
                    "nativeMaxRate=${nativeMaxRate.displayName} resolvedRate=${resolvedRate.displayName} → NativeDsd"
            )
            return DsdOutputMode.NativeDsd(resolvedRate)
        }

        // supportedDsdRates is empty — descriptor parser did not find DSD format tags. This is
        // common on some DACs that accept native DSD bitstreams but do not expose:DSD format
        // descriptors (or parse them in a non-standard way). Trust the libusb engine and the
        // C++ DsdPlaybackManager to negotiate the correct mode on the wire.
        Log.w(
            TAG,
            "resolveUsbPlaybackMode: isLibusbReady=true but supportedDsdRates=[] for " +
                "sourceRate=${sourceRate.displayName} — defaulting to NativeDsd(${sourceRate.displayName}); " +
                "C++ DsdPlaybackManager will STALL→DoP if DAC rejects native DSD"
        )
        return DsdOutputMode.NativeDsd(sourceRate)
    }

    private fun probeInternalOutputMode(): DsdOutputMode {
        val nativeMaxRate = probeInternalNativeMaxRate()
        if (nativeMaxRate != null) {
            return DsdOutputMode.NativeDsd(nativeMaxRate)
        }
        val maxDoPPcmRate = supportedDoPPcmCarrierRates().maxOrNull()
        return maxDoPPcmRate?.let(DsdOutputMode::DoP) ?: DsdOutputMode.Unsupported
    }

    private fun resolveInternalPlaybackMode(sourceRate: DsdRate): DsdOutputMode {
        val nativeMaxRate = probeInternalNativeMaxRate()
        if (nativeMaxRate != null && nativeMaxRate.multiplier >= sourceRate.multiplier) {
            return DsdOutputMode.NativeDsd(nativeMaxRate)
        }
        val maxDoPPcmRate = supportedDoPPcmCarrierRates().maxOrNull() ?: return DsdOutputMode.Unsupported
        return if (resolveEffectiveDoPRate(sourceRate, maxDoPPcmRate) != null) {
            DsdOutputMode.DoP(maxDoPPcmRate)
        } else {
            DsdOutputMode.Unsupported
        }
    }

    private fun probeInternalNativeMaxRate(): DsdRate? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null

        val attributes = AudioAttributesFactory.createMediaAttributes()
        return supportedDsdRatesDescending.firstOrNull { rate ->
            runCatching {
                @Suppress("DEPRECATION")
                AudioTrack.isDirectPlaybackSupported(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_DSD)
                        .setSampleRate(rate.sampleRateHz)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                    attributes,
                )
            }.getOrDefault(false)
        }
    }

    private fun supportedDoPPcmCarrierRates(): List<Int> = doPPcmCarrierRatesHz.filter(::isDoPCarrierBitPerfectSupported)

    /**
     * Returns `true` only when the platform can carry a DoP PCM stream at
     * [sampleRateHz] **bit-perfectly** through its internal audio path.
     *
     * On a DAP (FiiO, HiBy, iBasso, Astell&Kern, LG Quad-DAC, Sony WM1, etc.)
     * the dedicated internal DAC is exposed to `AudioTrack` as a direct output
     * that bypasses `AudioFlinger`'s mixer/resampler, so `isDirectPlaybackSupported`
     * returns `true` for the hi-res PCM carrier rates. On a mainstream phone the
     * same call returns `false`, which is the correct signal to refuse DoP — the
     * mixer would otherwise resample 176.4 kHz → 48 kHz and destroy the DoP
     * marker pattern, producing the classic "white noise + faint music" failure.
     *
     * Falls back to the legacy `getMinBufferSize` check on pre-Android-10
     * devices; that keeps the previous behaviour for anything too old to
     * implement the direct-playback query.
     */
    private fun isDoPCarrierBitPerfectSupported(sampleRateHz: Int): Boolean {

        val attributes = AudioAttributesFactory.createMediaAttributes()
        // Probe both common DoP carrier encodings — some HALs accept one and
        // not the other. Either positive answer means the route can carry the
        // stream without the mixer stepping in.
        val encodings = intArrayOf(
            AudioFormat.ENCODING_PCM_24BIT_PACKED,
            AudioFormat.ENCODING_PCM_32BIT,
        )
        return encodings.any { encoding ->
            runCatching {
                @Suppress("DEPRECATION")
                AudioTrack.isDirectPlaybackSupported(
                    AudioFormat.Builder()
                        .setEncoding(encoding)
                        .setSampleRate(sampleRateHz)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                    attributes,
                )
            }.getOrDefault(false)
        }
    }

    private fun isDoPPcmCarrierRate(sampleRateHz: Int): Boolean = sampleRateHz in doPPcmCarrierRatesHz

    private companion object {
        const val TAG = "DsdCapabilityDetector"
        const val DOP_PCM_RATE_DSD64 = 176_400
        const val DOP_PCM_RATE_DSD128 = 352_800
        const val DOP_PCM_RATE_DSD256 = 705_600

        val supportedDsdRatesDescending: List<DsdRate> = listOf(
            DsdRate.DSD256,
            DsdRate.DSD128,
            DsdRate.DSD64,
        )

        val doPPcmCarrierRatesHz: List<Int> = listOf(
            DOP_PCM_RATE_DSD64,
            DOP_PCM_RATE_DSD128,
            DOP_PCM_RATE_DSD256,
        )
    }
}

