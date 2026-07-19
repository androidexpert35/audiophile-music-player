package com.androidexpert35.audiophilemusicplayer.data.playback

import android.media.AudioManager
import com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile.SueIntensityProfile
import com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile.SueProfileResolver
import com.androidexpert35.audiophilemusicplayer.data.playback.native_.AudioFormatInfo
import com.androidexpert35.audiophilemusicplayer.data.playback.usb.UsbDacPresenceDetector
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Classifies which playback path owns the output-rate policy for a decoded track.
 *
 * The classifier is the single exclusion gate that decides whether the static
 * standard-PCM resolver is allowed to run. Only [PathType.STANDARD_PCM] may
 * proceed to [StaticOutputRateResolver]; every other path owns its output-rate
 * policy independently.
 *
 * @property usbDacPresenceDetector USB-route detector used to distinguish direct
 *   libusb playback from Android USB HAL playback.
 * @property audioManager System audio service used to inspect connected outputs.
 */
@Singleton
class PathClassifier @Inject constructor(
    private val usbDacPresenceDetector: UsbDacPresenceDetector,
    private val audioManager: AudioManager,
) {

    /**
     * Classifies the active playback path for [format].
     *
     * Ordering is intentional and must remain stable:
     * 1. DSD-originated content bypasses the resolver entirely.
     * 2. USB DAC routes bypass before any DSP-specific ownership check.
     * 3. SUE / Hi-Res Dynamic Remaster take ownership when their stage is active.
     * 4. Everything else is standard PCM eligible for static rate resolution.
     *
     * @param format Decoded source format captured for the current track.
     * @param sueEnabled Whether the SUE feature toggle is currently enabled.
     * @param hiResEnabled Whether the Hi-Res Dynamic Remaster toggle is currently enabled.
     * @return Resolved [PathType] for the current session.
     */
    fun classify(
        format: AudioFormatInfo,
        sueEnabled: Boolean,
        hiResEnabled: Boolean,
    ): PathType {
        if (format.isDsd || format.isResampledDsd) return PathType.DSD
        if (usbDacPresenceDetector.isLibusbOutputActive()) return PathType.USB_DAC_LIBUSB
        if (OutputDeviceHelper.hasAndroidUsbAudioOutput(audioManager)) return PathType.USB_DAC_ANDROID_HAL

        val sueResolution = SueProfileResolver.resolve(format)
        if (sueResolution.isLossySource &&
            sueEnabled &&
            sueResolution.intensityProfile != SueIntensityProfile.BYPASS
        ) {
            return PathType.SUE_LOSSY
        }

        if (!sueResolution.isLossySource && hiResEnabled && !format.isAlreadyNativeHiRes()) {
            return PathType.HI_RES_DAR
        }

        return PathType.STANDARD_PCM
    }
}

/**
 * Returns `true` when the source is already at or above the app's native hi-res
 * threshold and therefore bypasses the Hi-Res Dynamic Remaster stage.
 */
private fun AudioFormatInfo.isAlreadyNativeHiRes(): Boolean =
    sourceBitDepth >= 24 || sampleRateHz > 48_000

