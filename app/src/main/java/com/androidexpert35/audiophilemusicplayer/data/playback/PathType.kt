package com.androidexpert35.audiophilemusicplayer.data.playback

/**
 * Classifies the high-level playback path that owns output-rate negotiation for
 * the current audiophile session.
 *
 * The static output-rate resolver applies only to [STANDARD_PCM]. Every other
 * value represents a self-contained transport or DSP path whose sample-rate
 * policy must remain untouched.
 */
enum class PathType {
    /** Direct libusb USB DAC path that bypasses AudioFlinger entirely. */
    USB_DAC_LIBUSB,

    /** Platform-routed USB DAC path handled by Android's USB Audio HAL. */
    USB_DAC_ANDROID_HAL,

    /** Lossy-compressed source currently owned by the Sonic Upscaling Enhancer. */
    SUE_LOSSY,

    /** Lossless source currently owned by the Hi-Res Dynamic Remaster pipeline. */
    HI_RES_DAR,

    /** DSD-originated path (native DSD, DoP, or DSD-derived PCM fallback). */
    DSD,

    /** Standard PCM path routed through the platform AudioTrack / AudioFlinger stack. */
    STANDARD_PCM,
}

