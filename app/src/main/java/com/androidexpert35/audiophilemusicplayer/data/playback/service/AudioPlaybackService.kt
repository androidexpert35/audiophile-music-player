package com.androidexpert35.audiophilemusicplayer.data.playback.service

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.data.playback.AudioTelemetryCollector
import com.androidexpert35.audiophilemusicplayer.data.playback.AudiophileSimpleBasePlayer
import com.androidexpert35.audiophilemusicplayer.data.playback.PlaybackQueueClearer
import com.androidexpert35.audiophilemusicplayer.data.playback.PlaybackStateManager
import com.androidexpert35.audiophilemusicplayer.data.playback.engine.AudioEngineManager
import com.androidexpert35.audiophilemusicplayer.data.playback.engine.EnginePlaybackState
import com.androidexpert35.audiophilemusicplayer.data.playback.engine.EngineType
import com.androidexpert35.audiophilemusicplayer.data.playback.focus.AudioFocusController
import com.androidexpert35.audiophilemusicplayer.data.playback.focus.AudioFocusState
import com.androidexpert35.audiophilemusicplayer.data.playback.observeBecomingNoisy
import com.androidexpert35.audiophilemusicplayer.data.repository.SettingsPreferences
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Foreground [androidx.media3.session.MediaSessionService] that hosts the Audiophile bit-perfect
 * playback engine behind a Media3 `SimpleBasePlayer` façade
 * ([com.androidexpert35.audiophilemusicplayer.data.playback.AudiophileSimpleBasePlayer]).
 *
 * The compressed-stream-in / PCM-out pipeline lives entirely inside
 * [com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile.BitPerfectPlaybackEngine].
 * This service exists purely to keep the `MediaController` + notification stack alive,
 * handle becoming-noisy events, and persist the last session.
 *
 * Bit-perfect mixer routing (API 34+) is applied per-track inside
 * [com.androidexpert35.audiophilemusicplayer.data.playback.usb.UsbBitPerfectRouter] via
 * [com.androidexpert35.audiophilemusicplayer.data.playback.usb.UsbAudioSinkFactory]. The
 * confirmation signal flows through
 * [com.androidexpert35.audiophilemusicplayer.data.playback.AudioPathValidator] directly into
 * [AudioTelemetryCollector] — no manual push from this service is required.
 */
@AndroidEntryPoint
class AudioPlaybackService : MediaSessionService() {

    @Inject
    lateinit var audiophilePlayer: AudiophileSimpleBasePlayer

    @Inject
    lateinit var telemetryCollector: AudioTelemetryCollector

    @Inject
    lateinit var playbackStateManager: PlaybackStateManager

    @Inject
    lateinit var settingsPreferences: SharedPreferences

    @Inject
    lateinit var audioEngineManager: AudioEngineManager

    @Inject
    internal lateinit var audioFocusController: AudioFocusController

    private var mediaSession: MediaSession? = null

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    private var taskRemovalInProgress = false
    private var audioFocusJob: Job? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        val session = MediaSession.Builder(this, audiophilePlayer)
            .setCallback(createMediaSessionCallback())
            .setMediaButtonPreferences(
                listOf(
                    PlaybackSessionCommands.releaseUsbAudioButton(
                        getString(R.string.action_release_dac)
                    )
                )
            )
            .build()
        mediaSession = session

        playbackStateManager.attachToPlayer(audiophilePlayer)

        configureNotificationProvider()


        // Becoming-noisy broadcast → pause on headphone / BT disconnect.
        observeBecomingNoisy(applicationContext)
            .onEach { audiophilePlayer.playWhenReady = false }
            .launchIn(serviceScope)

        // Focus is deliberately a secondary release signal. The pause path
        // already closes libusb by itself; focus loss simply invokes the same
        // awaited operation when another app asks to take over playback.
        combine(
            audioEngineManager.state,
            audioEngineManager.activeEngineType,
        ) { state, engineType ->
            state == EnginePlaybackState.PLAYING && engineType == EngineType.AUDIOPHILE
        }
            .distinctUntilChanged()
            .onEach { needsFocus ->
                if (needsFocus) startAudioFocusObservation() else stopAudioFocusObservation()
            }
            .launchIn(serviceScope)

        Log.d(TAG, "AudioPlaybackService created — Audiophile bit-perfect engine ready")
    }

    @OptIn(UnstableApi::class)
    private fun configureNotificationProvider() {
        val provider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelName(R.string.notification_channel_playback)
            .build()
        provider.setSmallIcon(R.drawable.ic_notification_music)
        setMediaNotificationProvider(provider)
    }

    /** Requests focus for one active audiophile playback interval. */
    private fun startAudioFocusObservation() {
        if (audioFocusJob?.isActive == true) return
        audioFocusJob = audioFocusController.observeActiveFocus()
            .onEach { focusState ->
                if (focusState == AudioFocusState.DENIED || focusState == AudioFocusState.LOST) {
                    // Keep teardown independent from this focus collector. The
                    // PAUSED state cancels the collector (and abandons focus),
                    // but must not cancel the native close already in progress.
                    serviceScope.launch {
                        pauseAndReleaseOutput(reason = "audio focus $focusState")
                    }
                }
            }
            .launchIn(serviceScope)
    }

    /** Abandons the exact focus request associated with the playback interval. */
    private fun stopAudioFocusObservation() {
        audioFocusJob?.cancel()
        audioFocusJob = null
    }

    /**
     * Minimal [MediaSession.Callback] that restores a prior queue on AVRCP
     * resumption, so BT-headset play buttons work from a cold session.
     */
    @OptIn(UnstableApi::class)
    private fun createMediaSessionCallback(): MediaSession.Callback =
        object : MediaSession.Callback {

            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
            ): MediaSession.ConnectionResult {
                val commands = SessionCommands.Builder()
                    .addSessionCommands(
                        MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.commands
                    )
                    .add(PlaybackSessionCommands.releaseUsbAudio)
                    .build()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(commands)
                    .build()
            }

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle,
            ): ListenableFuture<SessionResult> {
                if (customCommand.customAction !=
                    PlaybackSessionCommands.releaseUsbAudio.customAction
                ) {
                    return super.onCustomCommand(session, controller, customCommand, args)
                }

                return serviceScope.future {
                    val released = pauseAndReleaseOutput(reason = "explicit release command")
                    SessionResult(
                        if (released) {
                            SessionResult.RESULT_SUCCESS
                        } else {
                            SessionResult.RESULT_ERROR_UNKNOWN
                        }
                    )
                }
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onPlaybackResumption(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
            ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                val player = mediaSession.player
                if (player.mediaItemCount == 0) {
                    return Futures.immediateFailedFuture(
                        UnsupportedOperationException("Queue empty; user must pick a track")
                    )
                }
                val items = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
                val startIndex = player.currentMediaItemIndex.coerceAtLeast(0)
                val startPos = player.currentPosition.coerceAtLeast(0L)
                return Futures.immediateFuture(
                    MediaSession.MediaItemsWithStartPosition(items, startIndex, startPos)
                )
            }
        }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    /**
     * Handles app removal from the recents list.
     *
     * Swiping away from recents is an explicit user signal to stop everything.
     * The service first waits for the explicit pause-time output teardown, then
     * stops itself so normal service cleanup can finish:
     *
     * ```
     * pauseAndReleaseOutput()
     *   → AudioEngineManager.pauseAndReleaseOutput()
     *     → BitPerfectPlaybackEngine.pauseAndReleaseOutput()
     *       → releaseOutputSinkInternal()
     *         → LibusbPcmAudioSink.close()  (or DSD / Enhanced variant)
     *           → UsbAudioBridge.nativeRelease(driverHandle)  // libusb_release_interface
     *           → UsbDeviceConnection.close()                 // returns FD to kernel UAC driver
     * ```
     *
     * This closes every app-owned libusb handle before task removal completes.
     * Whether an OEM kernel immediately rebinds its platform UAC driver remains
     * device-specific and is deliberately not treated as an app guarantee.
     *
     * @param rootIntent The intent that was used to start the task that has been removed.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val clearQueueOnExit = settingsPreferences.getBoolean(
            SettingsPreferences.KEY_CLEAR_QUEUE_ON_EXIT,
            SettingsPreferences.DEFAULT_CLEAR_QUEUE_ON_EXIT,
        )
        mediaSession?.player?.let { player ->
            if (clearQueueOnExit) {
                PlaybackQueueClearer.retainCurrentMediaItem(player)
                playbackStateManager.saveNow(player)
            } else {
                // Persist the current queue position and playhead so the next cold start
                // restores exactly where the user left off.
                playbackStateManager.saveNow(player)
            }
        }
        if (taskRemovalInProgress) return
        taskRemovalInProgress = true
        serviceScope.launch {
            // Wait for the DAC release before asking Android to destroy the
            // service. session.player.release() itself is asynchronous and is
            // therefore not a sufficient task-removal boundary.
            pauseAndReleaseOutput(reason = "task removed")
            stopSelf()
        }
    }

    /**
     * Performs the final Media3 and engine cleanup when Android destroys the
     * service.
     *
     * Explicit pause, focus-loss, and release commands do not depend on this
     * callback: they await the app-owned libusb teardown before reporting their
     * result. This method is the last-resort cleanup path for process lifecycle
     * events where Android still delivers service destruction.
     */
    override fun onDestroy() {
        serviceJob.cancel()

        mediaSession?.let { session ->
            playbackStateManager.saveNow(session.player)
            playbackStateManager.detachFromPlayer(session.player)
            telemetryCollector.reset()
            session.player.release()
            session.release()
        }
        mediaSession = null
        super.onDestroy()
        Log.d(TAG, "AudioPlaybackService destroyed")
    }

    /**
     * Pauses Media3 state and waits for the engine's native USB teardown.
     *
     * @param reason Short diagnostic cause without media identifiers.
     * @return `true` when teardown completed within the bounded service window.
     */
    private suspend fun pauseAndReleaseOutput(reason: String): Boolean {
        val released = withTimeoutOrNull(OUTPUT_RELEASE_TIMEOUT_MS) {
            audioEngineManager.pauseAndReleaseOutput()
        } ?: false
        if (released) {
            Log.i(TAG, "Audio output released: $reason")
        } else {
            Log.e(TAG, "Audio output release timed out after ${OUTPUT_RELEASE_TIMEOUT_MS}ms: $reason")
        }
        return released
    }

    private companion object {
        const val TAG = "AudioPlaybackService"
        const val OUTPUT_RELEASE_TIMEOUT_MS = 5_000L
    }
}
