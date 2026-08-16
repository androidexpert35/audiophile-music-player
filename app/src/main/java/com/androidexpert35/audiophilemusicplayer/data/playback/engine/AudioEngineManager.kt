package com.androidexpert35.audiophilemusicplayer.data.playback.engine

import android.util.Log
import com.androidexpert35.audiophilemusicplayer.data.playback.AUDIPHILE_PATH_TAG
import com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile.AudiophileEngine
import com.androidexpert35.audiophilemusicplayer.data.playback.engine.standard.StandardEngine
import com.androidexpert35.audiophilemusicplayer.data.playback.native_.AudioFormatInfo
import com.androidexpert35.audiophilemusicplayer.data.playback.native_.PipelinePathReport
import com.androidexpert35.audiophilemusicplayer.data.playback.usb.UsbVolumeController
import com.androidexpert35.audiophilemusicplayer.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runtime coordinator for the dual-engine playback architecture.
 *
 * Holds both concrete [AudioPlayerEngine] strategies (Audiophile + Standard),
 * keeps exactly one active, and mirrors its `StateFlow`s onto its own
 * re-exposed flows so consumers never need to re-subscribe when the strategy
 * changes. Also implements [AudioPlayerEngine] itself so
 * `AudiophileSimpleBasePlayer`, `AudioTelemetryCollector`, and future
 * settings ViewModels depend on a single stable type.
 *
 * ### Hot-swap contract
 * [switchTo] captures the active engine's URI + playhead + play-when-ready
 * flag, stops and detaches it, then loads the same URI into the target
 * engine at the saved position and resumes if the user was previously
 * playing. The swap runs under a [Mutex] so concurrent toggles are
 * serialised and only one engine ever holds the audio focus at a time.
 *
 * Default engine on app start is [EngineType.STANDARD]. When the user prefers
 * the audiophile engine, the manager activates it regardless of USB DAC state;
 * the engine itself decides whether to use direct USB output or a platform
 * AudioTrack fallback.
 *
 * @property audiophileEngine FFmpeg + AudioTrack strategy.
 * @property standardEngine   ExoPlayer (no offload) strategy.
 * @property appScope         Long-lived main-thread scope driving the
 *   mirroring collector jobs.
 */
@Singleton
class AudioEngineManager @Inject constructor(
    private val audiophileEngine: AudiophileEngine,
    private val standardEngine: StandardEngine,
    private val usbVolumeController: UsbVolumeController,
    @param:ApplicationScope private val appScope: CoroutineScope,
) : AudioPlayerEngine {

    private val swapMutex = Mutex()
    private val commandLock = Any()

    @Volatile
    private var active: AudioPlayerEngine = standardEngine

    private val _activeEngineType = MutableStateFlow(standardEngine.engineType)

    /** Observable engine selection — drives Settings UI + telemetry gating. */
    val activeEngineType: StateFlow<EngineType> = _activeEngineType.asStateFlow()

    override val engineType: EngineType
        get() = active.engineType

    // ── Re-exposed mirrors of the active engine's flows ──────────────────────

    private val _state = MutableStateFlow(active.state.value)
    override val state: StateFlow<EnginePlaybackState> = _state.asStateFlow()

    private val _positionMs = MutableStateFlow(active.positionMs.value)
    override val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(active.durationMs.value)
    override val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _currentUri = MutableStateFlow(active.currentUri.value)
    override val currentUri: StateFlow<String?> = _currentUri.asStateFlow()

    private val _currentFormat = MutableStateFlow(active.currentFormat.value)
    override val currentFormat: StateFlow<AudioFormatInfo?> = _currentFormat.asStateFlow()

    private val _pathReport = MutableStateFlow(active.pathReport.value)
    override val pathReport: StateFlow<PipelinePathReport?> = _pathReport.asStateFlow()

    override val lastErrorMessage: String?
        get() = active.lastErrorMessage

    /** Listener installed by the Media3 adapter. Forwarded from the active engine. */
    @Volatile
    private var externalListener: AudioPlayerEngine.Listener? = null

    /**
     * Single forwarding listener attached to whichever engine is active —
     * re-broadcasts callbacks to [externalListener] so the adapter never
     * needs to reattach on swap.
     */
    private val forwardingListener = object : AudioPlayerEngine.Listener {
        override fun onEngineStateChanged() { externalListener?.onEngineStateChanged() }
        override fun onTrackAdvanced() { externalListener?.onTrackAdvanced() }

        // The manager itself answers the EOF fallback query from its volatile
        // [queuedNextUri] cache — the last follower the playlist adapter asked
        // for — so a lost engine-side preload can be recovered without a
        // cross-thread round-trip into the adapter's main-thread state.
        override fun onNextTrackUriRequested(): String? =
            queuedNextUri ?: externalListener?.onNextTrackUriRequested()
    }

    /**
     * User-playback intent tracked independently of engine state so the
     * manager can restore `playWhenReady` across a hot-swap even when the
     * old engine has already transitioned to PAUSED mid-teardown.
     */
    @Volatile
    private var playWhenReadyIntent: Boolean = false

    /** Last follower URI requested by the playlist adapter for gapless preload. */
    @Volatile
    private var queuedNextUri: String? = null

    private var mirrorJob: Job? = null

    init {
        attachActive(standardEngine)
        // Keep the standard-engine (ExoPlayer) volume in sync with the persisted
        // app volume level. The audiophile path manages volume via the native
        // bridge directly; this observer only targets the standard path so that
        // volume keys work regardless of which engine is currently active.
        appScope.launch {
            usbVolumeController.volumePct.collect { pct ->
                val linear = pct / 100f
                standardEngine.setVolume(linear * linear) // quadratic taper, matching audiophile path
            }
        }
    }

    // ── Hot-swap API ─────────────────────────────────────────────────────────

    /**
     * Switches the active strategy, preserving URI, playhead, and play-when-ready.
     * No-op when [target] already matches the active engine.
     */
    suspend fun switchTo(target: EngineType) {
        if (active.engineType == target) return
        swapMutex.withLock {
            val transition = withCommandLock {
                val from = active
                val to = when (target) {
                    EngineType.AUDIOPHILE -> audiophileEngine
                    EngineType.STANDARD -> standardEngine
                }
                if (from === to) return@withCommandLock null

                // Snapshot the state we need to carry across the swap.
                val savedUri = from.currentUri.value
                val savedPosition = from.positionMs.value
                val wasPlaying = playWhenReadyIntent ||
                    from.state.value == EnginePlaybackState.PLAYING
                val savedQueuedNextUri = queuedNextUri

                detachActive(from)
                Log.i(
                    PATH_TAG,
                    "Switching engine ${from.engineType} -> ${to.engineType} " +
                        "hasTrack=${savedUri != null} wasPlaying=$wasPlaying"
                )
                EngineTransition(
                    from = from,
                    to = to,
                    savedUri = savedUri,
                    savedPosition = savedPosition,
                    wasPlaying = wasPlaying,
                    savedQueuedNextUri = savedQueuedNextUri,
                )
            } ?: return@withLock

            // Stop the old engine before the new one is wired up so only one
            // output path remains active. The audiophile path performs a
            // blocking USB-interface release here before ExoPlayer can reuse
            // the DAC through the platform stack.
            stopEngineForSwap(transition.from)

            withCommandLock {
                active = transition.to
                attachActive(transition.to)
                _activeEngineType.value = transition.to.engineType

                if (transition.savedUri != null) {
                    if (transition.wasPlaying) {
                        // Atomic load + auto-play: single handler post, no readiness race.
                        transition.to.load(
                            transition.savedUri,
                            transition.savedPosition,
                            autoPlay = true,
                        )
                    }
                    // wasPlaying=false: load is deferred to the await block below the lock
                    // so the command-lock is not held during the suspension.
                    transition.to.enqueueNext(transition.savedQueuedNextUri)
                } else {
                    transition.to.enqueueNext(null)
                }
                forwardingListener.onEngineStateChanged()
            }

            // When the swap preserved a paused track, load it and gate on READY outside
            // the command lock so that suspension does not block concurrent engine commands.
            // loadAndAwaitReady posts loadTrack to the audio thread, then suspends on the
            // state StateFlow until the decoder is non-null — making any subsequent
            // play() from the Media3 adapter unconditionally safe.
            //
            // The wait is bounded. The audiophile engine's Handler thread performs
            // blocking work while loading (ContentResolver.openFileDescriptor into
            // MediaProvider, FFmpeg open, USB claim), so a wedged provider or DAC can
            // keep the engine out of READY indefinitely. Without a timeout this
            // suspension holds [swapMutex] forever and every later switchTo — including
            // the user toggling audiophile mode back off — deadlocks behind it, which
            // presents as "the player stopped working after enabling Audiophile".
            if (transition.savedUri != null && !transition.wasPlaying) {
                val settled = withTimeoutOrNull(ENGINE_SWAP_READY_TIMEOUT_MS) {
                    loadAndAwaitReady(transition.savedUri, transition.savedPosition)
                }
                if (settled == null) {
                    Log.w(
                        PATH_TAG,
                        "Engine swap to ${transition.to.engineType} did not reach READY within " +
                            "${ENGINE_SWAP_READY_TIMEOUT_MS}ms — releasing the swap lock; the track " +
                            "stays loaded and a later play() will re-gate on engine state"
                    )
                }
            }
        }
    }

    /**
     * Reconciles the user's persisted engine preference with the active engine.
     *
     * @param preferAudiophile `true` when the user wants the FFmpeg-backed
     *   audiophile engine, `false` when the standard ExoPlayer engine should be active.
     */
    suspend fun reconcilePreferredEngine(preferAudiophile: Boolean) =
        switchTo(if (preferAudiophile) EngineType.AUDIOPHILE else EngineType.STANDARD)

    /**
     * Reloads the current track from the current playhead position when the
     * audiophile engine is active, so that runtime-setting changes (for example
     * the SoX, SUE, or Hi-Res Dynamic Remaster toggles) take effect immediately
     * without waiting for the next manual track change.
     *
     * The call is a no-op when the Standard engine is active — ExoPlayer
     * is not affected by the SoX resampler setting.
     */
    fun reloadCurrentTrack() {
        if (active is AudiophileEngine) audiophileEngine.reloadWithCurrentSettings()
    }

    /**
     * Returns `true` when the active audiophile track already suppresses the
     * standalone SoX stage because SUE or Hi-Res remaster owns the resampling
     * path, or because the current transport is native DSD / DoP.
     */
    fun isStandaloneSoxSuppressedForCurrentTrack(): Boolean {
        return (active as? AudiophileEngine)
            ?.isStandaloneSoxSuppressedForCurrentTrack()
            ?: false
    }

    // ── AudioPlayerEngine delegation ─────────────────────────────────────────

    override fun load(uri: String, startPositionMs: Long, autoPlay: Boolean) {
        withCommandLock {
            playWhenReadyIntent = autoPlay
            active.load(uri, startPositionMs, autoPlay)
        }
    }

    override fun enqueueNext(uri: String?) {
        withCommandLock {
            queuedNextUri = uri
            active.enqueueNext(uri)
        }
    }

    override fun play() {
        withCommandLock {
            playWhenReadyIntent = true
            active.play()
        }
    }

    override fun pause() {
        withCommandLock {
            playWhenReadyIntent = false
            // The audiophile engine keeps the exclusive USB claim across a pause
            // and defers the release to its idle-sink scheduler (2 min). Closing
            // the sink immediately on every pause made the DAC disappear from
            // the platform audio stack and re-appear on resume, so Android
            // re-enumerated it and flashed the system volume panel on every
            // pause/play; it also made quick resumes rebuild the whole USB
            // session. Other apps can still reach the DAC after the idle
            // timeout, or immediately via releaseUsbSinkNow() when a focus-loss
            // hook needs it.
            active.pause()
        }
    }

    override fun stop() {
        withCommandLock {
            playWhenReadyIntent = false
            queuedNextUri = null
            active.stop()
        }
    }

    override fun seekTo(positionMs: Long) {
        withCommandLock {
            active.seekTo(positionMs)
        }
    }

    override fun release() {
        withCommandLock {
            detachActive(active)
            playWhenReadyIntent = false
            queuedNextUri = null
            // Both engines hold independent resources — release each directly.
            audiophileEngine.release()
            standardEngine.release()
        }
    }

    override fun setListener(listener: AudioPlayerEngine.Listener?) {
        externalListener = listener
    }

    // ── Internal wiring ──────────────────────────────────────────────────────

    /** Installs the forwarding listener and starts mirroring flows from [engine]. */
    private fun attachActive(engine: AudioPlayerEngine) {
        engine.setListener(forwardingListener)
        Log.i(PATH_TAG, "Attached active engine=${engine.engineType}")
        updateMirroredSnapshot(engine)
        startMirroring(engine)
    }

    private fun updateMirroredSnapshot(engine: AudioPlayerEngine) {
        _state.value = engine.state.value
        _positionMs.value = engine.positionMs.value
        _durationMs.value = engine.durationMs.value
        _currentUri.value = engine.currentUri.value
        _currentFormat.value = engine.currentFormat.value
        _pathReport.value = engine.pathReport.value
    }

    private fun startMirroring(engine: AudioPlayerEngine) {
        mirrorJob?.cancel()
        mirrorJob = appScope.launch {
            engine.state.onEach { _state.value = it }.launchIn(this)
            engine.positionMs.onEach { _positionMs.value = it }.launchIn(this)
            engine.durationMs.onEach { _durationMs.value = it }.launchIn(this)
            engine.currentUri.onEach { _currentUri.value = it }.launchIn(this)
            engine.currentFormat.onEach { _currentFormat.value = it }.launchIn(this)
            engine.pathReport.onEach { _pathReport.value = it }.launchIn(this)
        }
    }

    private fun detachActive(engine: AudioPlayerEngine) {
        engine.setListener(null)
        mirrorJob?.cancel()
        mirrorJob = null
    }


    /**
     * Stops [engine] before the incoming strategy takes over the output.
     *
     * `stopAndReleaseOutput` completes only once the audiophile Handler thread
     * has drained the posted teardown, and that thread can be parked in a
     * blocking USB release or content-resolver call. The wait is bounded for the
     * same reason as the readiness gate in [switchTo]: an unbounded one strands
     * [swapMutex] and wedges every later engine toggle.
     */
    private suspend fun stopEngineForSwap(engine: AudioPlayerEngine) {
        when (engine) {
            is AudiophileEngine -> {
                val stopped = withTimeoutOrNull(ENGINE_SWAP_STOP_TIMEOUT_MS) {
                    engine.stopAndReleaseOutput()
                }
                if (stopped == null) {
                    Log.w(
                        PATH_TAG,
                        "Audiophile engine teardown exceeded ${ENGINE_SWAP_STOP_TIMEOUT_MS}ms — " +
                            "continuing the swap; the pending release still runs on the audio thread"
                    )
                }
            }
            else -> engine.stop()
        }
    }

    private inline fun <T> withCommandLock(block: () -> T): T =
        synchronized(commandLock, block)

    /** Immutable snapshot of the state carried across an engine swap. */
    private data class EngineTransition(
        val from: AudioPlayerEngine,
        val to: AudioPlayerEngine,
        val savedUri: String?,
        val savedPosition: Long,
        val wasPlaying: Boolean,
        val savedQueuedNextUri: String?,
    )

    private companion object {
        const val PATH_TAG = AUDIPHILE_PATH_TAG

        /**
         * Upper bound on waiting for the incoming engine to reach READY during a swap.
         *
         * Generous enough for a cold FFmpeg open of a large DSD file over a slow
         * content provider, short enough that a wedged audio thread cannot hold
         * the swap lock past a user's patience.
         */
        const val ENGINE_SWAP_READY_TIMEOUT_MS = 8_000L

        /** Upper bound on waiting for the outgoing engine to release its output. */
        const val ENGINE_SWAP_STOP_TIMEOUT_MS = 5_000L
    }
}
