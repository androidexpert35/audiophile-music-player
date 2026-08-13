package com.androidexpert35.audiophilemusicplayer.data.repository

/**
 * Central key registry for the app's persistent settings.
 *
 * Keeping the preference file name, keys, and defaults in one place avoids
 * accidental drift between the repository and Hilt providers that both need
 * access to the same settings surface.
 */
object SettingsPreferences {

    /** Name of the dedicated SharedPreferences file storing app settings. */
    const val PREFS_NAME: String = "audiophile_settings"

    /** Master toggle enabling direct USB audiophile playback when available. */
    const val KEY_AUDIOPHILE_ENABLED: String = "audiophile_engine_enabled"

    /** Default to the audiophile engine on a first app launch. */
    const val DEFAULT_AUDIOPHILE_ENABLED: Boolean = true

    /**
     * SharedPreferences key for the Sonic Upscaling Enhancer (SUE) toggle.
     *
     * When `true` and the active source is lossy-compressed, the audiophile engine
     * inserts the SUE filter graph (conditional 48 kHz pre-upsampling, harmonic
     * excitation, high-band contouring, guarded stereo widening, soft low-pass,
     * and dithering) before the SoX resampler. The stage is silently bypassed
     * for lossless sources even when this key is `true`.
     */
    const val KEY_SUE_ENABLED: String = "audiophile_sue_enabled"

    /** SUE is opt-in so lossy playback remains unprocessed by default. */
    const val DEFAULT_SUE_ENABLED: Boolean = false

    /**
     * SharedPreferences key for the Hi-Res Dynamic Remaster toggle.
     *
     * When `true` and the active source is lossless (FLAC, WAV, ALAC), the
     * audiophile engine inserts the Hi-Res Remaster filter graph (96 kHz
     * oversampling, upward dynamic expansion via `compand`, and triangular HP
     * dithering) before the output sink. The stage is silently bypassed for
     * lossy sources even when this key is `true`.
     */
    const val KEY_HIRES_REMASTER_ENABLED: String = "audiophile_hires_remaster_enabled"

    /** Hi-Res Dynamic Remaster is opt-in so lossless playback remains unprocessed by default. */
    const val DEFAULT_HIRES_REMASTER_ENABLED: Boolean = false

    /** Stores the encoded per-section sort and list/grid choices for the library. */
    const val KEY_LIBRARY_DISPLAY_PREFERENCES: String = "library_display_preferences"


    /**
     * SharedPreferences key holding the document-tree URIs of every folder the user
     * authorised as a music location.
     *
     * Stored as a `StringSet`. The library scan is scoped **exclusively** to these
     * folders: an empty set means no catalogue can be built and onboarding must ask
     * the user to pick a folder. Each entry has a matching long-lived read grant taken
     * through `ContentResolver.takePersistableUriPermission`, which is also the only
     * mechanism that makes DSD containers readable — Android does not classify
     * `.dsf` / `.dff` as audio and therefore never covers them with `READ_MEDIA_AUDIO`.
     */
    const val KEY_MUSIC_FOLDER_URIS: String = "music_folder_uris"

    /** Records which mandatory folder-selection flow the installation has completed. */
    const val KEY_MUSIC_FOLDER_SELECTION_VERSION: String = "music_folder_selection_version"

    /** Current folder-selection contract required before the library may be indexed. */
    const val CURRENT_MUSIC_FOLDER_SELECTION_VERSION: Int = 1

    /** Prefix for the USB software-volume preference stored independently per DAC. */
    const val KEY_USB_VOLUME_PCT_PREFIX: String = "usb_volume_pct_device_"

    /**
     * Default USB volume on first install.
     *
     * Each newly encountered DAC starts at 60% before its first PCM sample is
     * produced. The native quadratic taper maps that position to a gain of 0.36
     * (about −8.9 dB), providing headroom while remaining readily audible.
     */
    const val DEFAULT_USB_VOLUME_PCT: Int = 60
}
