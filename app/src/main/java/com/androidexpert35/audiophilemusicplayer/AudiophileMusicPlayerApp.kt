package com.androidexpert35.audiophilemusicplayer

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.androidexpert35.audiophilemusicplayer.data.playback.engine.AudioEngineSettingsCoordinator
import com.androidexpert35.audiophilemusicplayer.data.playback.engine.Force48kSettingsCoordinator
import com.androidexpert35.audiophilemusicplayer.data.playback.engine.HiResRemasterSettingsCoordinator
import com.androidexpert35.audiophilemusicplayer.data.playback.engine.SueSettingsCoordinator
import com.androidexpert35.audiophilemusicplayer.data.playback.usb.UsbAudioLifecycleManager
import com.tony.coreui.data.strings.CoreUiStringProvider
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point enabling Hilt dependency injection across the entire process.
 *
 * Responsibilities:
 * - Triggers Hilt component generation via [@HiltAndroidApp].
 * - Initialises [CoreUiStringProvider] so that non-composable collaborators can resolve strings
 *   without holding an Activity reference.
 * - Registers the Hilt-provided [ImageLoader] as the Coil process singleton so that
 *   all `SubcomposeAsyncImage` / `AsyncImage` calls across the app use the shared
 *   OkHttp client — enabling HTTPS Deezer image URLs to be loaded with crossfade
 *   and without a separate connection pool.
 *
 * All other initialisation logic lives in Hilt modules so that this class remains
 * as thin as possible.
 */
@HiltAndroidApp
class AudiophileMusicPlayerApp : Application() {

    /**
     * Singleton Coil [ImageLoader] wired with the shared OkHttpClient.
     *
     * Injected by Hilt after the super.onCreate() call inside the generated
     * Hilt_AudiophileMusicPlayerApp base class, so it is safe to read in [onCreate].
     */
    @Inject
    lateinit var imageLoader: ImageLoader

    /**
     * Starts long-lived USB attach/detach monitoring so direct USB playback can
     * request permission, react to DAC removal, and reconcile engine selection
     * before any playback begins.
     */
    @Inject
    lateinit var usbAudioLifecycleManager: UsbAudioLifecycleManager

    /**
     * Starts long-lived reconciliation between the persisted audiophile-mode
     * preference and the active playback engine as soon as the process boots.
     */
    @Inject
    lateinit var audioEngineSettingsCoordinator: AudioEngineSettingsCoordinator


    /**
     * Starts long-lived observation of the SUE toggle, reloading the current
     * audiophile track whenever the preference changes so the enhancement state
     * updates immediately for the active file.
     */
    @Inject
    lateinit var sueSettingsCoordinator: SueSettingsCoordinator

    /**
     * Starts long-lived observation of the Hi-Res Dynamic Remaster toggle,
     * reloading the current audiophile track whenever the preference changes so
     * lossless remastering responds independently from the separate SUE toggle.
     */
    @Inject
    lateinit var hiResRemasterSettingsCoordinator: HiResRemasterSettingsCoordinator

    /**
     * Lifecycle hook retained for API stability. Currently a no-op because the
     * standard-PCM rate policy is device-agnostic (source rate is the only input),
     * so output-route changes never require a pipeline rebuild.
     */
    @Inject
    lateinit var force48kSettingsCoordinator: Force48kSettingsCoordinator

    override fun onCreate() {
        super.onCreate()
        CoreUiStringProvider.init(this)

        // Register the Hilt-configured ImageLoader as the Coil singleton so that
        // HTTPS artwork URLs (Deezer) are fetched via the same OkHttp connection
        // pool used by Retrofit — no duplicate TLS handshakes, no extra pool.
        SingletonImageLoader.setSafe { imageLoader }

        // Apply the stored FFmpeg-vs-standard engine preference before the
        // playback service receives its first controller command.
        audioEngineSettingsCoordinator.start()


        // Observe SUE toggle changes and rebuild the active audiophile session so
        // lossy/lossless gating updates immediately for the current track.
        sueSettingsCoordinator.start()

        // Observe Hi-Res remaster toggle changes and rebuild the active
        // audiophile session so the lossless remaster path updates immediately
        // without mutating the separate SUE preference.
        hiResRemasterSettingsCoordinator.start()

        // Lifecycle hook — currently a no-op; the source-rate-only policy means
        // output-device changes never alter the rate decision.
        force48kSettingsCoordinator.start()

        // Start app-wide USB lifecycle observation before the playback service
        // ever receives its first `MediaController` command.
        usbAudioLifecycleManager.start()
    }
}

