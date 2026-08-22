package com.androidexpert35.audiophilemusicplayer.data.repository

import com.androidexpert35.audiophilemusicplayer.data.repository.SettingsPreferences.DEFAULT_USB_VOLUME_PCT
import com.androidexpert35.audiophilemusicplayer.data.repository.SettingsPreferences.KEY_LIBRARY_DISPLAY_PREFERENCES
import com.androidexpert35.audiophilemusicplayer.data.repository.SettingsPreferences.KEY_USB_VOLUME_PCT_PREFIX


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

    /** SharedPreferences key controlling whether the queue is retained after task removal. */
    const val KEY_CLEAR_QUEUE_ON_EXIT: String = "clear_queue_on_exit"

    /** Preserve the current session by default to retain existing listener behaviour. */
    const val DEFAULT_CLEAR_QUEUE_ON_EXIT: Boolean = false

    /** Stores the encoded per-section sort and list/grid choices for the library. */
    const val KEY_LIBRARY_DISPLAY_PREFERENCES: String = "library_display_preferences"

    /**
     * SharedPreferences key for the user's chosen display order of the library sections.
     *
     * Stored as a comma-delimited [com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library.LibraryContentType]
     * name sequence rather than inside [KEY_LIBRARY_DISPLAY_PREFERENCES]'s `StringSet`, because a
     * `StringSet` does not guarantee iteration order.
     */
    const val KEY_LIBRARY_SECTION_ORDER: String = "library_section_order"

    /** Matches the section order the app has always shown, so existing users see no change. */
    val DEFAULT_LIBRARY_SECTION_ORDER: List<String> = listOf(
        "TRACKS", "PLAYLISTS", "ALBUMS", "ARTISTS", "GENRES", "YEARS", "COMPOSERS"
    )


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
     * Pre-1.1 key holding a single USB volume level shared by every DAC.
     *
     * Superseded by [KEY_USB_VOLUME_PCT_PREFIX], but still **read** as the seed
     * for any DAC that has no per-device level yet. Dropping it outright reset
     * every upgrading listener to [DEFAULT_USB_VOLUME_PCT]; for anyone who had
     * deliberately set 100% — the only position the native taper maps to exact
     * unity, and therefore the only bit-perfect one — that silently inserted a
     * −8.9 dB digital attenuation into a path whose entire purpose is to avoid
     * one. Never written again: the first [KEY_USB_VOLUME_PCT_PREFIX] write for
     * a device takes over permanently.
     */
    const val LEGACY_KEY_USB_VOLUME_PCT: String = "usb_volume_pct"

    /**
     * Default USB volume for a DAC with no stored level and no legacy value.
     *
     * Each newly encountered DAC starts at 60% before its first PCM sample is
     * produced. The native quadratic taper maps that position to a gain of 0.36
     * (about −8.9 dB), providing headroom while remaining readily audible.
     */
    const val DEFAULT_USB_VOLUME_PCT: Int = 60
}
