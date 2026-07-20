package com.androidexpert35.audiophilemusicplayer.presentation.activity

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.androidexpert35.audiophilemusicplayer.data.playback.AudioTelemetryCollector
import com.androidexpert35.audiophilemusicplayer.data.playback.usb.UsbVolumeController
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioFormat
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.DsdOutputMode
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.OutputStreamInfo
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track
import com.androidexpert35.audiophilemusicplayer.domain.usecase.IsMediaLibraryIndexedUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.PlayTrackUseCase
import com.androidexpert35.audiophilemusicplayer.presentation.activity.MainActivity.Companion.DEFAULT_OVERLAY_VOLUME_PCT
import com.androidexpert35.audiophilemusicplayer.presentation.activity.MainActivity.Companion.FAST_STEP
import com.androidexpert35.audiophilemusicplayer.presentation.activity.MainActivity.Companion.FAST_THRESHOLD
import com.androidexpert35.audiophilemusicplayer.presentation.activity.MainActivity.Companion.FINE_STEP
import com.androidexpert35.audiophilemusicplayer.presentation.activity.MainActivity.Companion.OVERLAY_DISMISS_DELAY_MS
import com.androidexpert35.audiophilemusicplayer.presentation.navigation.AppNavigator
import com.androidexpert35.audiophilemusicplayer.presentation.navigation.AppStartDestination
import com.androidexpert35.audiophilemusicplayer.presentation.navigation.overlay.PlayerOverlayManager
import com.androidexpert35.audiophilemusicplayer.presentation.screen.common.components.UsbVolumeOverlay
import com.androidexpert35.audiophilemusicplayer.presentation.theme.AudiophileMusicPlayerTheme
import com.tony.coreui.presentation.navigation.NavigationManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Single-activity shell hosting the Compose navigation graph.
 *
 * Enables edge-to-edge rendering for the immersive player experience
 * and delegates all UI to [AppNavigator].
 *
 * Triggers a telemetry reload on every [onCreate], [onStart], and [onResume]
 * so that audio format data is recovered whenever the activity is created fresh
 * (process kill + restore) or returned to from the background.
 *
 * [AudioTelemetryCollector.requestReload] synchronously rebuilds the telemetry
 * snapshot from the current engine [kotlinx.coroutines.flow.StateFlow] values,
 * which covers two practical scenarios:
 * - **Cold-start session restore** — the engine loads the track asynchronously;
 *   the rebuild closes the window where the UI would otherwise show idle telemetry.
 * - **Post idle-sink release** — after a long pause the engine's output sink is
 *   released internally without emitting a new flow event; the rebuild re-derives
 *   the correct bit-perfect / path status from the preserved path report.
 *
 * When launched via an [Intent.ACTION_VIEW] intent (e.g. opening an audio
 * file from a file manager), the activity starts playback of the external URI
 * immediately and opens the player overlay. [android:launchMode="singleTop"]
 * ensures subsequent file-open intents are routed through [onNewIntent] rather
 * than creating a duplicate activity instance.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var navigationManager: NavigationManager

    @Inject
    lateinit var playerOverlayManager: PlayerOverlayManager

    /**
     * Singleton telemetry collector shared with the playback service.
     * Injected here so [onCreate] and [onResume] can request a reload of
     * the current audio format without direct access to the player instance.
     */
    @Inject
    lateinit var telemetryCollector: AudioTelemetryCollector

    /**
     * Use case injected to start playback of an externally-opened audio file URI
     * without coupling the activity to a ViewModel lifecycle.
     */
    @Inject
    lateinit var playTrackUseCase: PlayTrackUseCase

    /**
     * Use case that reports whether a cached Room library index already exists, used
     * to decide the launch graph before the [AppNavigator] composes its NavHost.
     */
    @Inject
    lateinit var isMediaLibraryIndexedUseCase: IsMediaLibraryIndexedUseCase

    /**
     * Volume controller for the direct libusb audio path.
     *
     * Volume keys are intercepted in [dispatchKeyEvent] and routed here instead
     * of the system volume dialog, because libusb bypasses AudioFlinger and its
     * volume mixers entirely. The overlay state below is updated in parallel so
     * the user sees immediate feedback.
     */
    @Inject
    lateinit var usbVolumeController: UsbVolumeController

    // ── Volume overlay state ──────────────────────────────────────────────────────
    // Compose state that drives UsbVolumeOverlay visibility and level.
    // These are plain activity-level Compose state holders so they survive
    // recompositions without a ViewModel.

    /** Whether the transient volume overlay is currently shown. */
    private var showVolumeOverlay by mutableStateOf(false)

    /**
     * The volume percentage currently displayed in [UsbVolumeOverlay].
     *
     * Initialised to [DEFAULT_OVERLAY_VOLUME_PCT] rather than reading from
     * [usbVolumeController] here, because `@Inject lateinit var` fields are not
     * populated until after the constructor returns (Hilt injects in [onCreate]).
     * The value is synced from the controller on the first key press.
     */
    private var overlayVolumePct by mutableIntStateOf(DEFAULT_OVERLAY_VOLUME_PCT)

    /**
     * `true` when Native DSD is the active transport mode.
     *
     * Native DSD bypasses the software volume scalar in the C++ bridge entirely —
     * the DAC receives raw one-bit DSD data with no gain stage applied. When this
     * flag is set, volume key presses are still consumed (to suppress the system
     * volume HUD) but no call to [UsbVolumeController] is made. The overlay is
     * shown in a non-interactive "locked" state to explain the limitation to the
     * user. Derived reactively from [AudioTelemetryCollector.telemetry].
     */
    private var isDsdLocked by mutableStateOf(false)

    /**
     * `true` while a native libusb pump bridge is attached and the direct-USB
     * audio path is routing audio.
     *
     * When `false`, volume keys adjust the system [AudioManager] stream volume
     * instead of the libusb software gain scalar, and the overlay label switches
     * from "USB Volume" to "Volume". Synced from
     * [UsbVolumeController.isLibusbPathActive] in [onCreate].
     */
    private var isLibusbActive by mutableStateOf(false)

    /**
     * Lazy reference to the system AudioManager, used to read and adjust
     * [AudioManager.STREAM_MUSIC] volume when the libusb path is not active.
     *
     * Retrieved once from [getSystemService] after Hilt has finished injecting
     * all fields. `null` only if the system service is unexpectedly unavailable.
     */
    private var audioManager: AudioManager? = null

    /** Auto-hide job that dismisses the overlay after [OVERLAY_DISMISS_DELAY_MS]. */
    private var overlayDismissJob: Job? = null

    /**
     * Launch graph the [AppNavigator] should enter, resolved once per cold start.
     *
     * Starts as [AppStartDestination.Deciding] because the persisted indexing flag is
     * read asynchronously; [resolveStartDestination] updates it to the real value. When
     * it resolves to [AppStartDestination.Main] the onboarding screen is never composed,
     * removing the composition + navigation-transition burst that stuttered on launch.
     */
    private var startDestination by mutableStateOf<AppStartDestination>(AppStartDestination.Deciding)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Resolve AudioManager once; used when the libusb path is not active to
        // control the system STREAM_MUSIC volume without showing the system HUD.
        audioManager = getSystemService(AudioManager::class.java)

        // Decide the launch graph before the first composition so an already-indexed
        // library goes straight to the main flow, skipping onboarding entirely.
        resolveStartDestination()

        setContent {
            AudiophileMusicPlayerTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    AppNavigator(
                        navigationManager = navigationManager,
                        playerOverlayManager = playerOverlayManager,
                        startDestination = startDestination
                    )

                    UsbVolumeOverlay(
                        isVisible = showVolumeOverlay,
                        volumePct = overlayVolumePct,
                        isDsdLocked = isDsdLocked,
                        isUsbMode = isLibusbActive,
                        onVolumeChange = { pct ->
                            overlayVolumePct = pct
                            if (isLibusbActive) {
                                usbVolumeController.setVolumePct(pct)
                            } else {
                                // Map the slider percentage to the nearest system
                                // volume step and apply it silently (flags = 0
                                // suppresses the system HUD).
                                applySystemVolumePct(pct)
                            }
                            rescheduleOverlayDismiss()
                        },
                        modifier = Modifier.statusBarsPadding(),
                    )
                }
            }
        }

        // Track whether Native DSD is active so volume key presses can bypass
        // the software volume scalar when the DAC receives raw one-bit DSD data.
        lifecycleScope.launch {
            telemetryCollector.telemetry
                .map { telemetry ->
                    val info = telemetry.streamInfo
                    info is OutputStreamInfo.Dsd && info.outputMode is DsdOutputMode.NativeDsd
                }
                .collect { nativeDsdActive -> isDsdLocked = nativeDsdActive }
        }

        // Mirror the libusb bridge attachment state so handleVolumeKey can choose
        // between the software gain scalar (USB) and AudioManager (system) paths.
        lifecycleScope.launch {
            usbVolumeController.isLibusbPathActive.collect { active ->
                isLibusbActive = active
                // When switching away from libusb, sync the overlay to the current
                // system volume so the first key press shows a coherent level.
                if (!active) {
                    overlayVolumePct = readSystemVolumePct()
                }
            }
        }

        telemetryCollector.requestReload()
        handleIncomingIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        telemetryCollector.requestReload()
    }

    override fun onResume() {
        super.onResume()
        telemetryCollector.requestReload()
    }

    /**
     * Called when the activity is already running (singleTop) and a new intent arrives,
     * for example when the user opens a second audio file from a file manager while
     * Audiophile is already in the foreground.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    /**
     * Intercepts physical volume key DOWN events and routes them to [usbVolumeController]
     * so the direct libusb audio path can be volume-controlled independently of the
     * system mixer (which AudioFlinger bypasses when libusb is active).
     *
     * Returning `true` consumes the event, preventing the system volume dialog from
     * appearing. [onKeyUp] also returns `true` for the same key codes so the system
     * does not act on the release event either.
     *
     * ### Repeat acceleration
     *
     * Android delivers repeated [onKeyDown] events while a key is held, each with an
     * incrementing [KeyEvent.getRepeatCount]. Two thresholds drive acceleration:
     *
     * | Repeat count         | Step size | Rationale                              |
     * |----------------------|-----------|----------------------------------------|
     * | 0                    | 1 %       | Single tap — fine-grained adjustment   |
     * | 1 … [FAST_THRESHOLD] | 1 %       | Short hold — still precise             |
     * | > [FAST_THRESHOLD]   | [FAST_STEP] % | Extended hold — scroll quickly   |
     *
     * The auto-dismiss timer is reset on every event so the overlay stays visible
     * throughout a hold gesture and hides [OVERLAY_DISMISS_DELAY_MS] ms after the
     * last repeat.
     *
     * @param keyCode The integer key code matching one of the [KeyEvent.KEYCODE_VOLUME_*]
     *   constants.
     * @param event   The full key event carrying [KeyEvent.getRepeatCount].
     * @return `true` when the event is consumed; `false` to delegate to the framework.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                handleVolumeKey(up = true, repeatCount = event.repeatCount)
                true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                handleVolumeKey(up = false, repeatCount = event.repeatCount)
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    /**
     * Consumes volume key UP events so the system does not act on the release
     * and show the system volume HUD.
     *
     * @param keyCode The integer key code.
     * @param event   The full key event.
     * @return `true` for volume keys; delegates to super for all others.
     */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> true
            else -> super.onKeyUp(keyCode, event)
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────────

    /**
     * Resolves which navigation graph [AppNavigator] should enter on this launch.
     *
     * The media-permission check is synchronous, so a missing permission resolves to
     * [AppStartDestination.Onboarding] immediately (before the first composition). When
     * permission is present the persisted indexing flag is read on a coroutine — a fast
     * single-row Room lookup — and the result flips [startDestination] to either
     * [AppStartDestination.Main] (skip onboarding) or [AppStartDestination.Onboarding]
     * (run the first scan). The [AppStartDestination.Deciding] window shows only a plain
     * themed background, so nothing flashes during the brief asynchronous read.
     *
     * Onboarding remains the single owner of the first-time permission and scan flow;
     * this gate only avoids re-entering it once there is genuinely nothing to do.
     */
    private fun resolveStartDestination() {
        val hasMediaPermission = ContextCompat.checkSelfPermission(
            this,
            requiredMediaPermission()
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasMediaPermission) {
            startDestination = AppStartDestination.Onboarding
            return
        }

        lifecycleScope.launch {
            startDestination = if (isMediaLibraryIndexedUseCase()) {
                AppStartDestination.Main
            } else {
                AppStartDestination.Onboarding
            }
        }
    }

    /**
     * Resolves the runtime permission gating audio-library access for the current
     * platform: `READ_MEDIA_AUDIO` on Android 13+, `READ_EXTERNAL_STORAGE` below it.
     */
    private fun requiredMediaPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    /**
     * Adjusts the active audio path volume by one or more steps and refreshes the overlay.
     *
     * Two routing paths are supported:
     *
     * **libusb active (`isLibusbActive == true`)**
     * Routes to [UsbVolumeController] so the quartic gain scalar applied in the
     * C++ pump thread is updated. No [AudioManager] interaction occurs.
     *
     * **System AudioFlinger path (`isLibusbActive == false`)**
     * Calls [AudioManager.adjustStreamVolume] with `flags = 0` to update
     * [AudioManager.STREAM_MUSIC] silently, suppressing the system volume HUD.
     * The overlay percentage is then read back from [AudioManager] so it always
     * reflects the actual step the platform clamped to.
     *
     * When [isDsdLocked] is `true` neither path is touched — the overlay is shown
     * in its locked DSD state to inform the user that the DAC controls volume.
     *
     * Step size is determined by [repeatCount]:
     * - `0` or up to [FAST_THRESHOLD]: [FINE_STEP] (1 %) for precise adjustment.
     * - Above [FAST_THRESHOLD]: [FAST_STEP] % per event for rapid traversal.
     *
     * @param up          `true` to increment, `false` to decrement.
     * @param repeatCount [KeyEvent.getRepeatCount] from the triggering event.
     */
    private fun handleVolumeKey(up: Boolean, repeatCount: Int) {
        if (isDsdLocked) {
            // Native DSD bypasses the software gain stage entirely — show the
            // overlay in locked mode so the user understands why the rocker has
            // no effect.
            showVolumeOverlay = true
            rescheduleOverlayDismiss()
            return
        }

        val step = if (repeatCount > FAST_THRESHOLD) FAST_STEP else FINE_STEP

        if (isLibusbActive) {
            // libusb path — drive the quartic gain scalar in the C++ bridge.
            if (up) usbVolumeController.stepUp(step) else usbVolumeController.stepDown(step)
            overlayVolumePct = usbVolumeController.volumePct.value
        } else {
            // System AudioFlinger path — adjust AudioManager stream volume
            // without showing the system HUD (flags = 0).
            val direction = if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
            // Each ADJUST_RAISE / ADJUST_LOWER moves by exactly one system step,
            // so we call it `step` times to match the USB single/fast cadence.
            repeat(step) {
                audioManager?.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
            }
            // Read back the actual clamped value so the overlay always shows the
            // real system level rather than a speculatively computed one.
            overlayVolumePct = readSystemVolumePct()
        }

        showVolumeOverlay = true
        rescheduleOverlayDismiss()
    }

    /**
     * Reads the current [AudioManager.STREAM_MUSIC] volume as a percentage in
     * `[0, 100]`.
     *
     * Returns [overlayVolumePct] as a fallback when [audioManager] is unexpectedly
     * unavailable.
     */
    private fun readSystemVolumePct(): Int {
        val am = audioManager ?: return overlayVolumePct
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return 0
        return (am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max * 100).roundToInt()
    }

    /**
     * Converts a slider percentage back to an [AudioManager] volume step and
     * applies it silently to [AudioManager.STREAM_MUSIC].
     *
     * `flags = 0` prevents the system volume HUD from appearing; the custom
     * overlay is used instead.
     *
     * @param pct Target volume in `[0, 100]`.
     */
    private fun applySystemVolumePct(pct: Int) {
        val am = audioManager ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return
        val step = (pct.toFloat() / 100f * max).roundToInt().coerceIn(0, max)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, step, 0)
    }

    /**
     * Cancels any pending auto-hide job and starts a fresh [OVERLAY_DISMISS_DELAY_MS]
     * countdown that hides [showVolumeOverlay] when it expires.
     *
     * Called both by [handleVolumeKey] (physical key press) and the [UsbVolumeOverlay]
     * slider's `onVolumeChange` callback (touch drag) so both code paths share the
     * same dismissal behaviour.
     */
    private fun rescheduleOverlayDismiss() {
        overlayDismissJob?.cancel()
        overlayDismissJob = lifecycleScope.launch {
            delay(OVERLAY_DISMISS_DELAY_MS)
            showVolumeOverlay = false
        }
    }

    /**
     * Inspects [intent] for an [Intent.ACTION_VIEW] action with an audio URI.
     * When found, builds a minimal [Track] from the URI — ExoPlayer resolves the
     * actual metadata (title, duration) once the media source is prepared — starts
     * playback, and navigates to the player overlay.
     *
     * The display title is derived from the last path segment of the URI so the
     * now-playing screen shows a recognisable label before metadata is available.
     *
     * @param intent The intent to inspect.
     */
    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data?.toString() ?: return

        val displayName = intent.data?.lastPathSegment
            ?.substringBeforeLast('.')
            ?.ifBlank { null }
            ?: "Unknown"

        // Build a minimal Track — Media3 will resolve real duration/metadata from the URI.
        val externalTrack = Track(
            id = uri.hashCode().toLong(),
            title = displayName,
            artistName = "",
            albumTitle = "",
            albumId = 0L,
            durationMs = 0L,
            uri = uri,
            trackNumber = 0,
            discNumber = 1,
            audioFormat = AudioFormat.UNKNOWN,
            fileSizeBytes = 0L,
            dateAdded = 0L
        )

        lifecycleScope.launch {
            playTrackUseCase(externalTrack, listOf(externalTrack))
            // Open the player overlay after handing off to the playback engine.
            playerOverlayManager.open()
        }
    }

    private companion object {
        /** Duration in milliseconds before the volume overlay auto-dismisses. */
        const val OVERLAY_DISMISS_DELAY_MS = 4_000L

        /**
         * Safe default for [overlayVolumePct] at construction time, before Hilt has
         * injected [usbVolumeController]. Matches [UsbVolumeController]'s own default
         * so the displayed value is consistent the moment the first key event fires.
         */
        const val DEFAULT_OVERLAY_VOLUME_PCT = 50

        /**
         * Step size for a single tap or a short hold (fine-grained adjustment).
         *
         * With [UsbVolumeController.MAX_VOLUME_PCT] = 100, a step of `1` gives
         * exactly 100 discrete positions — one per percentage point.
         */
        const val FINE_STEP = 1

        /**
         * Step size applied once [FAST_THRESHOLD] repeat events have been received
         * (i.e. the button has been held for roughly [FAST_THRESHOLD] × 50 ms).
         *
         * 5 % per event means the full range can be traversed in ≈ 1 second of
         * holding, keeping rapid scrubbing comfortable without overshooting on
         * accidental long presses.
         */
        const val FAST_STEP = 5

        /**
         * Number of [KeyEvent] repeat cycles before [FAST_STEP] kicks in.
         *
         * Android fires key-repeat events at roughly 50 ms intervals after the
         * initial key-down, so 8 repeats ≈ 400 ms of continuous hold.
         */
        const val FAST_THRESHOLD = 8
    }
}
