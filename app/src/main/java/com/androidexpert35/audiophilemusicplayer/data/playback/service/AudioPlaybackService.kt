package com.androidexpert35.audiophilemusicplayer.data.playback.service

import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.data.playback.AudioTelemetryCollector
import com.androidexpert35.audiophilemusicplayer.data.playback.AudiophileSimpleBasePlayer
import com.androidexpert35.audiophilemusicplayer.data.playback.PlaybackQueueClearer
import com.androidexpert35.audiophilemusicplayer.data.playback.PlaybackStateManager
import com.androidexpert35.audiophilemusicplayer.data.playback.observeBecomingNoisy
import com.androidexpert35.audiophilemusicplayer.data.repository.SettingsPreferences
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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

    private var mediaSession: MediaSession? = null

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        val session = MediaSession.Builder(this, audiophilePlayer)
            .setCallback(createMediaSessionCallback())
            .build()
        mediaSession = session

        playbackStateManager.attachToPlayer(audiophilePlayer)

        configureNotificationProvider()


        // Becoming-noisy broadcast → pause on headphone / BT disconnect.
        observeBecomingNoisy(applicationContext)
            .onEach { audiophilePlayer.playWhenReady = false }
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

    /**
     * Minimal [MediaSession.Callback] that restores a prior queue on AVRCP
     * resumption, so BT-headset play buttons work from a cold session.
     */
    @OptIn(UnstableApi::class)
    private fun createMediaSessionCallback(): MediaSession.Callback =
        object : MediaSession.Callback {

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
     * The service is stopped unconditionally here so that [onDestroy] always
     * fires, running the full USB DAC teardown chain:
     *
     * ```
     * session.player.release()
     *   → AudioEngineManager.release()
     *     → BitPerfectPlaybackEngine.release()
     *       → releaseCurrentPlaybackResources()
     *         → LibusbPcmAudioSink.close()  (or DSD / Enhanced variant)
     *           → UsbAudioBridge.nativeRelease(driverHandle)  // libusb_release_interface
     *           → UsbDeviceConnection.close()                 // returns FD to kernel UAC driver
     * ```
     *
     * Without the unconditional [stopSelf] call, the service previously stayed
     * alive whenever the player had items loaded (paused or playing), holding
     * the USB file descriptor and preventing other apps (Spotify, YouTube, etc.)
     * from accessing the DAC until the user performed a Force Stop.
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
        // Stopping the service here triggers onDestroy(), which releases the
        // Media3 session, the engine, and ultimately the USB FD via sink.close().
        stopSelf()
    }

    /**
     * Full service teardown — always reached via [stopSelf] from [onTaskRemoved]
     * or from the system when the app is backgrounded and memory is reclaimed.
     *
     * ### USB DAC release guarantee
     *
     * `session.player.release()` triggers the chain that ends in each active
     * libusb output sink calling `UsbAudioBridge.nativeRelease()` (releases the
     * libusb interface) followed by `UsbDeviceConnection.close()` (returns the
     * file descriptor to the Android USB host kernel driver).  Once the FD is
     * closed the kernel re-attaches the UAC2 driver and AudioFlinger regains
     * access — Spotify, YouTube, and other apps resume immediately without
     * requiring a cable re-plug or Force Stop.
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


    private companion object {
        const val TAG = "AudioPlaybackService"
    }
}
