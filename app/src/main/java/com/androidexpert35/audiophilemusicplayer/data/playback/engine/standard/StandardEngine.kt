package com.androidexpert35.audiophilemusicplayer.data.playback.engine.standard

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.androidexpert35.audiophilemusicplayer.data.playback.POSITION_TICK_INTERVAL_MS
import com.androidexpert35.audiophilemusicplayer.data.playback.cancelAndClear
import com.androidexpert35.audiophilemusicplayer.data.playback.engine.AudioPlayerEngine
import com.androidexpert35.audiophilemusicplayer.data.playback.engine.EnginePlaybackState
import com.androidexpert35.audiophilemusicplayer.data.playback.engine.EngineType
import com.androidexpert35.audiophilemusicplayer.data.playback.native_.AudioFormatInfo
import com.androidexpert35.audiophilemusicplayer.data.playback.native_.PipelinePathReport
import com.androidexpert35.audiophilemusicplayer.data.playback.restartTicker
import com.androidexpert35.audiophilemusicplayer.data.playback.runOrPost
import com.androidexpert35.audiophilemusicplayer.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Battery-saving [com.androidexpert35.audiophilemusicplayer.data.playback.engine.AudioPlayerEngine] strategy backed by Media3 ExoPlayer.
 *
 * The player is built lazily on the main looper (ExoPlayer's required
 * construction thread) the first time a control method is invoked, and it
 * is torn down in [release].
 *
 * ### Stability choices
 * * **No audio offload** — we deliberately do NOT call
 *   `setAudioOffloadPreferences`. The platform `MediaCodec` → `AudioFlinger`
 *   path has consistent behaviour across OEMs and avoids the subtle
 *   duty-cycling glitches offload still ships on some devices.
 * * **No audio-focus coupling** — the `handleAudioFocus` flag passed to
 *   [androidx.media3.exoplayer.ExoPlayer.Builder.setAudioAttributes] is deliberately
 *   `false`; playback state is controlled exclusively by explicit player commands.
 *
 * ### Standard-mode telemetry
 * The engine synthesizes best-effort [currentFormat] and [pathReport] values
 * from Media3's renderer audio format, the currently selected audio track, and
 * metadata resolved from the active URI. This keeps the shared telemetry
 * collector alive after switching away from the Audiophile engine, even though
 * direct-path / bit-perfect routing details remain unavailable in the standard
 * pipeline.
 *
 * @property context  Application context used to build [androidx.media3.exoplayer.ExoPlayer].
 * @property appScope Long-lived main-thread scope driving the position ticker.
 */
@Singleton
@OptIn(UnstableApi::class)
class StandardEngine @Inject internal constructor(
    @param:ApplicationContext private val context: Context,
    @param:ApplicationScope private val appScope: CoroutineScope,
    private val trackMetadataResolver: StandardTrackMetadataResolver,
) : AudioPlayerEngine {

    override val engineType: EngineType = EngineType.STANDARD

    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow(EnginePlaybackState.IDLE)
    override val state: StateFlow<EnginePlaybackState> = _state.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    override val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    override val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _currentUri = MutableStateFlow<String?>(null)
    override val currentUri: StateFlow<String?> = _currentUri.asStateFlow()

    private val _currentFormat = MutableStateFlow<AudioFormatInfo?>(null)
    override val currentFormat: StateFlow<AudioFormatInfo?> = _currentFormat.asStateFlow()

    private val _pathReport = MutableStateFlow<PipelinePathReport?>(null)
    override val pathReport: StateFlow<PipelinePathReport?> = _pathReport.asStateFlow()

    @Volatile
    override var lastErrorMessage: String? = null
        private set

    private var listener: AudioPlayerEngine.Listener? = null
    private var player: ExoPlayer? = null
    @Volatile private var pendingVolume: Float = 1.0f
    private var positionTickerJob: Job? = null
    private var playWhenReady: Boolean = false
    private var currentTrackMetadata: StandardTrackMetadata? = null
    private var currentTrackMetadataUri: String? = null

    /** Forwards ExoPlayer callbacks to the unified [EnginePlaybackState] model. */
    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) = updateEngineState()
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateEngineState()
            if (isPlaying) startPositionTicker() else stopPositionTicker()
        }

        override fun onTracksChanged(tracks: Tracks) {
            refreshTelemetry(player)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            _currentUri.value = mediaItem?.localConfiguration?.uri?.toString()
            refreshTelemetry(player)
        }


        override fun onPlayerError(error: PlaybackException) {
            lastErrorMessage = error.message ?: "ExoPlayer failure (${error.errorCodeName})"
            _state.value = EnginePlaybackState.ERROR
            notifyStateChanged()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            // AUTO_TRANSITION fires when ExoPlayer advances to the next queue
            // item naturally — mirrors the gapless path of the Audiophile engine.
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                _currentUri.value = player?.currentMediaItem?.localConfiguration?.uri?.toString()
                refreshTelemetry(player)
                listener?.onTrackAdvanced()
            }
        }
    }

    // ── Public API ───────────────────────────────────────────────────────────

    override fun load(uri: String, startPositionMs: Long, autoPlay: Boolean) = runOnMain {
        val p = ensurePlayer()
        val safeStartPositionMs = startPositionMs.coerceAtLeast(0L)
        playWhenReady = autoPlay
        _state.value = EnginePlaybackState.LOADING
        // Clear the previous track's snapshot immediately so the telemetry UI never
        // shows stale codec / rate / path details while the next item is preparing.
        clearTelemetry()
        notifyStateChanged()

        p.setMediaItem(MediaItem.fromUri(uri.toUri()), safeStartPositionMs)
        p.prepare()
        p.playWhenReady = autoPlay
        _currentUri.value = uri
        _positionMs.value = safeStartPositionMs
    }

    override fun enqueueNext(uri: String?) = runOnMain {
        val p = player ?: return@runOnMain
        // Replace everything AFTER the current item with the requested follower.
        // The follower index must be derived from currentMediaItemIndex: after an
        // AUTO_TRANSITION the playing item sits at index 1 (or higher across
        // consecutive advances), and the previous hardcoded removeMediaItems(1, …)
        // deleted the item that had just started playing — killing playback at
        // the first automatic track change.
        val followerIndex = if (p.mediaItemCount == 0) 0 else p.currentMediaItemIndex + 1
        if (p.mediaItemCount > followerIndex) p.removeMediaItems(followerIndex, p.mediaItemCount)
        if (uri != null) p.addMediaItem(MediaItem.fromUri(uri.toUri()))
    }

    override fun play() = runOnMain {
        playWhenReady = true
        player?.playWhenReady = true
    }

    override fun pause() = runOnMain {
        playWhenReady = false
        player?.playWhenReady = false
    }

    override fun stop() = runOnMain {
        playWhenReady = false
        player?.stop()
        player?.clearMediaItems()
        resetPlaybackSnapshot()
        publishIdleState()
    }

    override fun seekTo(positionMs: Long) = runOnMain {
        val safePositionMs = positionMs.coerceAtLeast(0L)
        player?.seekTo(safePositionMs)
        _positionMs.value = safePositionMs
        notifyStateChanged()
    }

    override fun release() = runOnMain {
        stopPositionTicker()
        player?.removeListener(playerListener)
        player?.release()
        player = null
        resetPlaybackSnapshot()
        publishIdleState()
    }

    override fun setListener(listener: AudioPlayerEngine.Listener?) {
        this.listener = listener
    }

    override fun setVolume(linearGain: Float) = runOnMain {
        pendingVolume = linearGain
        player?.volume = linearGain
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        // NOTE: do NOT call setAudioOffloadPreferences — standard MediaCodec path only.
        val built = ExoPlayer.Builder(context)
            .setAudioAttributes(attrs, /* handleAudioFocus = */ false)
            .setHandleAudioBecomingNoisy(false) // adapter already owns becoming-noisy handling
            // Local-file playback with the screen off: without a wake lock the CPU
            // may suspend right at a track boundary (the audio HAL's own lock lapses
            // between AudioTracks) and the next item never starts. The audiophile
            // engine holds its own PARTIAL_WAKE_LOCK; this is the ExoPlayer analogue.
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        built.addListener(playerListener)
        built.volume = pendingVolume
        player = built
        return built
    }

    private fun updateEngineState() {
        val p = player ?: run {
            clearTelemetry()
            publishIdleState()
            return
        }
        refreshTelemetry(p)
        val duration = p.duration
        _durationMs.value = if (duration == C.TIME_UNSET) 0L else duration
        _state.value = when (p.playbackState) {
            Player.STATE_IDLE -> EnginePlaybackState.IDLE
            Player.STATE_BUFFERING -> EnginePlaybackState.LOADING
            Player.STATE_ENDED -> EnginePlaybackState.ENDED
            Player.STATE_READY -> resolveReadyState(p)
            else -> EnginePlaybackState.IDLE
        }
        notifyStateChanged()
    }

    private fun startPositionTicker() {
        positionTickerJob = appScope.restartTicker(
            currentJob = positionTickerJob,
            intervalMs = POSITION_TICK_INTERVAL_MS,
        ) {
            val p = player ?: return@restartTicker
            _positionMs.value = p.currentPosition.coerceAtLeast(0L)
            notifyStateChanged()
        }
    }

    private fun stopPositionTicker() {
        positionTickerJob = positionTickerJob.cancelAndClear()
    }

    private fun refreshTelemetry(player: ExoPlayer?) {
        val metadata = resolveTrackMetadata(player)
        val audioFormat = player?.currentTracks?.resolveTelemetryAudioFormat()
        _currentFormat.value = audioFormat?.toStandardAudioFormatInfo(metadata)
        _pathReport.value = audioFormat?.let {
            createStandardPathReport(
                format = it,
                metadata = metadata,
            )
        }
    }

    private fun clearTelemetry() {
        _currentFormat.value = null
        _pathReport.value = null
        currentTrackMetadata = null
        currentTrackMetadataUri = null
    }

    private fun getCachedTrackMetadata(uri: String): StandardTrackMetadata? {
        if (currentTrackMetadataUri == uri) return currentTrackMetadata

        currentTrackMetadataUri = uri
        currentTrackMetadata = trackMetadataResolver.resolve(uri)
        return currentTrackMetadata
    }

    private fun resolveTrackMetadata(player: ExoPlayer?): StandardTrackMetadata? =
        (_currentUri.value ?: player?.currentMediaItem?.localConfiguration?.uri?.toString())
            ?.let(::getCachedTrackMetadata)

    private fun resolveReadyState(player: ExoPlayer): EnginePlaybackState = when {
        player.isPlaying -> EnginePlaybackState.PLAYING
        playWhenReady -> EnginePlaybackState.LOADING
        player.currentPosition > 0L -> EnginePlaybackState.PAUSED
        else -> EnginePlaybackState.READY
    }

    private fun resetPlaybackSnapshot() {
        _currentUri.value = null
        _positionMs.value = 0L
        _durationMs.value = 0L
        clearTelemetry()
        stopPositionTicker()
    }

    private fun publishIdleState() {
        _state.value = EnginePlaybackState.IDLE
        notifyStateChanged()
    }

    private fun notifyStateChanged() {
        listener?.onEngineStateChanged()
    }

    /** Ensures the given block runs on the ExoPlayer main looper. */
    private inline fun runOnMain(crossinline block: () -> Unit) {
        mainHandler.runOrPost(block)
    }
}
