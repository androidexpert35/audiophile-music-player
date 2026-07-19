package com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import com.androidexpert35.audiophilemusicplayer.data.playback.AUDIPHILE_PATH_TAG
import com.androidexpert35.audiophilemusicplayer.data.playback.Decision
import com.androidexpert35.audiophilemusicplayer.data.playback.POSITION_TICK_INTERVAL_MS
import com.androidexpert35.audiophilemusicplayer.data.playback.PathType
import com.androidexpert35.audiophilemusicplayer.data.playback.cancelAndClear
import com.androidexpert35.audiophilemusicplayer.data.playback.engine.AudioPlayerEngine
import com.androidexpert35.audiophilemusicplayer.data.playback.engine.EnginePlaybackState
import com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile.BitPerfectPlaybackEngine.Companion.PAUSED_IDLE_SINK_RELEASE_MS
import com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile.BitPerfectPlaybackEngine.Companion.RECOVERABLE_ERROR_RETRY_DELAY_MS
import com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile.BitPerfectPlaybackEngine.Companion.ROUTING_HEARTBEAT_TICKS
import com.androidexpert35.audiophilemusicplayer.data.playback.native_.AudioFormatInfo
import com.androidexpert35.audiophilemusicplayer.data.playback.native_.FFmpegDecoder
import com.androidexpert35.audiophilemusicplayer.data.playback.native_.PipelinePathReport
import com.androidexpert35.audiophilemusicplayer.data.playback.native_.SueInfo
import com.androidexpert35.audiophilemusicplayer.data.playback.restartTicker
import com.androidexpert35.audiophilemusicplayer.data.playback.usb.LibusbOutputSink
import com.androidexpert35.audiophilemusicplayer.data.playback.usb.UsbAudioSinkFactory
import com.androidexpert35.audiophilemusicplayer.data.repository.SettingsPreferences
import com.androidexpert35.audiophilemusicplayer.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * The Audiophile bit-perfect playback engine.
 *
 * Owns a single [FFmpegDecoder] feeding a single [AudiophileOutputSink] on a
 * dedicated audio thread (`Process.THREAD_PRIORITY_AUDIO`). All mutations of
 * the decoder, sink, and write-loop state happen on that thread; public
 * methods ([play], [pause], [stop], [seekTo], [loadTrack], [release]) post
 * work through a [Handler] attached to the thread's looper, so they are
 * safely callable from any thread.
 *
 * ### Responsibilities
 * This class acts as the main coordinator. Each sub-concern is delegated to a
 * dedicated helper:
 * - **[BitPerfectWakeLockController]** — partial wake-lock lifecycle.
 * - **[BitPerfectIdleSinkReleaseScheduler]** — idle-sink-release countdown.
 * - **[BitPerfectTransportBuffers]** — PCM and DSD scratch-buffer management.
 * - **[BitPerfectGaplessQueue]** — next-track preload state for gapless transitions.
 * - **[BitPerfectSessionLoader]** — tiered decoder + sink session building (T1–T3).
 * - **[BitPerfectSinkRouter]** — sink selection and DSD transport context creation.
 * - **[BitPerfectPcmRatePolicy]** — PCM sample-rate ownership resolution.
 * - **[BitPerfectRoutingDiagnostics]** — logcat routing banners and heartbeat lines.
 * - **[resolveUriToPath]** — `content://` → `/proc/self/fd/<fd>` resolution.
 *
 * ### Pause and CPU utilization
 *
 * The write loop is **event-driven**, not poll-based. Each iteration is
 * dispatched as a `Handler` message; when `playWhenReady` is `false` the
 * iteration self-terminates without re-posting, leaving the audio thread
 * blocked in its Looper's kernel `epoll_wait`. There is **zero CPU spin
 * during pause** — no `while(isPaused)` loop, no `Thread.sleep` polling.
 *
 * ### Idle-sink release
 *
 * When the engine enters [EnginePlaybackState.PAUSED] it arms a
 * [Handler.postDelayed] countdown of [PAUSED_IDLE_SINK_RELEASE_MS]
 * (2 minutes). If the player remains paused for the full duration,
 * `releaseIdleSinkInternal()` closes the [AudiophileOutputSink] so the OS
 * can reclaim audio focus and USB-DAC resources. The [FFmpegDecoder] and all
 * position state are preserved so the next [play] call transparently rebuilds
 * the sink and seeks back to the exact pause point.
 *
 * ### Gapless strategy
 *
 * When [enqueueNext] is called and the currently-playing track's
 * [AudioFormatInfo] matches the enqueued one (sample rate, encoding, channel
 * count), the engine opens the next decoder on the audio thread while the
 * current track finishes, then atomically swaps decoders at end-of-stream
 * **without** stopping or flushing the current output sink.
 *
 * @property context Application context for `ContentResolver` resolution.
 * @property appScope Long-lived scope used only for the lightweight position
 *   ticker; all actual playback work runs on the dedicated audio thread.
 * @property sessionLoader Orchestrates the three-tier session-building chain.
 * @property sinkRouter Selects the correct output sink for each format/path.
 * @property ratePolicy Resolves PCM sample-rate ownership per track load.
 * @property usbAudioSinkFactory Factory for platform and direct-USB sinks.
 */
@Singleton
internal class BitPerfectPlaybackEngine @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:ApplicationScope private val appScope: CoroutineScope,
    private val sessionLoader: BitPerfectSessionLoader,
    private val sinkRouter: BitPerfectSinkRouter,
    private val ratePolicy: BitPerfectPcmRatePolicy,
    private val usbAudioSinkFactory: UsbAudioSinkFactory,
) {

    // ── Threading ────────────────────────────────────────────────────────────

    private val audioThread = HandlerThread(
        "AudiophileAudio",
        Process.THREAD_PRIORITY_AUDIO,
    ).apply { start() }
    private val handler = Handler(audioThread.looper)

    // ── Sub-concern helpers ──────────────────────────────────────────────────

    private val wakeLockController = BitPerfectWakeLockController(context, "$TAG:Playback")

    private val idleSinkScheduler = BitPerfectIdleSinkReleaseScheduler(
        handler = handler,
        delayMs = PAUSED_IDLE_SINK_RELEASE_MS,
        onRelease = ::releaseIdleSinkInternal,
    )

    private val transportBuffers = BitPerfectTransportBuffers()

    private val gaplessQueue = BitPerfectGaplessQueue()

    // ── State (all mutations happen on `handler`) ────────────────────────────

    private val _state = MutableStateFlow(EnginePlaybackState.IDLE)
    val state: StateFlow<EnginePlaybackState> = _state.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _currentFormat = MutableStateFlow<AudioFormatInfo?>(null)
    val currentFormat: StateFlow<AudioFormatInfo?> = _currentFormat.asStateFlow()

    private val _pathReport = MutableStateFlow<PipelinePathReport?>(null)
    val pathReport: StateFlow<PipelinePathReport?> = _pathReport.asStateFlow()

    private val _currentUri = MutableStateFlow<String?>(null)
    val currentUri: StateFlow<String?> = _currentUri.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    /**
     * Human-readable description of the most recent failure that drove the
     * engine into [EnginePlaybackState.ERROR]. Consumed by the Media3 adapter
     * to build a [androidx.media3.common.PlaybackException] so errors are
     * never silent.
     */
    @Volatile
    var lastErrorMessage: String? = null
        private set

    /**
     * Event sink used by [com.androidexpert35.audiophilemusicplayer.data.playback.AudiophileSimpleBasePlayer]
     * to drive Media3 `invalidateState()` calls on every interesting transition.
     */
    private var engineListener: AudioPlayerEngine.Listener? = null

    fun setListener(listener: AudioPlayerEngine.Listener?) {
        engineListener = listener
    }

    // ── Mutable internals (audio thread only) ────────────────────────────────

    private var currentDecoder: FFmpegDecoder? = null
    private var sink: AudiophileOutputSink? = null

    /**
     * Active Sonic Upscaling Enhancer stage for the current track, or `null`
     * when the source is lossless, SUE is disabled, or the engine is idle.
     * Owned exclusively by the audio thread.
     */
    private var activeSueStage: SueStage? = null

    /** Playhead tracking — computed from `AudioTrackSink.playbackHeadPositionFrames`. */
    private var playStartPositionMs: Long = 0L
    private var sinkStartFrames: Long = 0L
    private var playbackClockRateHz: Int = 0

    private var playWhenReady: Boolean = false
    private var writeLoopRunning: Boolean = false
    private var activeDsdPlaybackContext: DsdPlaybackContext? = null
    private var currentPathType: PathType = PathType.STANDARD_PCM
    private var currentRateDecision: Decision = Decision.Bypass
    private var currentActiveOutputThreadSampleRateHz: Int = 0

    /** Immutable spill-state snapshot for DoP encoding across write-loop boundaries. */
    private var dopSpillState = DoPSpillState(ByteArray(4), 0)

    private var positionTickerJob: Job? = null

    /**
     * Counter incremented on every position-ticker tick while playing.
     * When it reaches [ROUTING_HEARTBEAT_TICKS] the current routing path is
     * re-logged and the counter resets to zero.
     *
     * Audio-thread only — no synchronisation needed.
     */
    private var routingHeartbeatTick: Int = 0

    // ── Pre-allocated Runnables (zero-allocation hot path) ───────────────────
    //
    // handler.post(::writeLoopIteration) would allocate a new Runnable wrapper on
    // every decoded chunk — thousands per minute — causing GC pressure on the
    // audio thread. Stable references here eliminate all hot-path heap churn.

    /**
     * Stable [Runnable] wrapping [writeLoopIteration], reused on every
     * `handler.post(writeLoopRunnable)` call so the PCM-write path performs
     * zero heap allocations.
     */
    private val writeLoopRunnable: Runnable = Runnable(::writeLoopIteration)

    /**
     * Stable [Runnable] for the [RECOVERABLE_ERROR_RETRY_DELAY_MS] retry path.
     * Kept separate from [writeLoopRunnable] so [Handler.removeCallbacks] can
     * target either scheduling slot independently.
     */
    private val writeLoopRecoveryRunnable: Runnable = Runnable(::writeLoopIteration)

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Loads and prepares [uri] at [startPositionMs]. Does **not** auto-play.
     *
     * Cancels any pending idle-sink-release timer before replacing the current
     * session so the countdown from a prior pause can never fire against stale
     * resources.
     */
    fun loadTrack(uri: String, startPositionMs: Long = 0L) = handler.post {
        idleSinkScheduler.cancel()
        doLoadTrack(uri, startPositionMs, autoPlay = false)
    }

    /**
     * Loads and immediately starts playing [uri] at [startPositionMs].
     *
     * Cancels any pending idle-sink-release timer so the countdown from a prior
     * pause can never fire against the new session.
     */
    fun loadAndPlay(uri: String, startPositionMs: Long = 0L) = handler.post {
        idleSinkScheduler.cancel()
        doLoadTrack(uri, startPositionMs, autoPlay = true)
    }

    /** Preloads the next track for gapless playback when formats match. */
    fun enqueueNext(uri: String?) = handler.post {
        if (uri == null) {
            gaplessQueue.clear()
            return@post
        }
        doEnqueueNext(uri)
    }

    fun play() = handler.post {
        if (currentDecoder == null) {
            // Log the drop explicitly — a silent return here is the direct cause of
            // Case A (cold-start no-auto-play) and Case B (sequential playback failure).
            BitPerfectDiagnosticsLogger.onPlayDroppedNoDecoder(_state.value)
            return@post
        }

        // Cancel any pending idle-sink-release timeout so a long pause followed
        // immediately by a resume does not tear down the sink we are about to use.
        idleSinkScheduler.cancel()

        // The output sink was released after the idle timeout fired while the
        // player was paused. Rebuilding it by hand here (as this used to do)
        // bypassed the tiered session loader, which is the only place that
        // resolves the on-disk `sourcePath` the libusb route requires — so a
        // manual rebuild always fell through to the platform AudioTrack sink,
        // silently dropping direct-USB routing after every idle pause. Route
        // through the same full reload path as a settings-driven reload so the
        // rebuilt session negotiates libusb / DSD / SUE exactly like a fresh
        // track load does.
        if (sink == null) {
            val uri = _currentUri.value ?: return@post
            val resumePosition = _positionMs.value
            Log.i(TAG, "Reloading track to rebuild sink after idle release; resuming at ${resumePosition}ms")
            doLoadTrack(uri, resumePosition, autoPlay = true)
            return@post
        }

        runCatching {
            wakeLockController.withRollback {
                sink?.play()
                wakeLockController.acquire()
                playWhenReady = true
                _state.value = EnginePlaybackState.PLAYING
                notifyEngineStateChanged()
                startWriteLoopIfNeeded()
                startPositionTicker()
                // Re-log routing on manual resume so logcat always shows the
                // active path when audio starts flowing.
                sink?.let { activeSink ->
                    _currentFormat.value?.let { fmt ->
                        BitPerfectRoutingDiagnostics.logRoutingBanner(activeSink, fmt, trigger = "resume")
                    }
                }
            }
        }.onFailure { throwable ->
            playWhenReady = false
            stopPositionTicker()
            reportPlaybackError(
                logMessage = "play failed: ${throwable.message}",
                userMessage = "Audio playback could not be started: ${throwable.message}",
                throwable = throwable,
            )
        }
    }

    fun pause() = handler.post {
        playWhenReady = false
        try {
            sink?.pause()
            val pausedPositionMs = snapshotCurrentPlaybackPositionMs()
            // Re-anchor to the exact sink head captured at the pause boundary.
            // This prevents the next resume/rebuild from falling back to the
            // last UI ticker value, which may be hundreds of milliseconds old.
            playStartPositionMs = pausedPositionMs
            sinkStartFrames = sink?.getPlaybackHeadPositionFrames() ?: 0L
            _positionMs.value = pausedPositionMs
            _state.value = EnginePlaybackState.PAUSED
            notifyEngineStateChanged()
            stopPositionTicker()
            idleSinkScheduler.schedule()
        } catch (throwable: Throwable) {
            stopPositionTicker()
            reportPlaybackError(
                logMessage = "pause failed: ${throwable.message}",
                userMessage = "Audio playback could not be paused: ${throwable.message}",
                throwable = throwable,
            )
        } finally {
            wakeLockController.release()
        }
    }

    fun stop() = handler.post {
        idleSinkScheduler.cancel()
        doStopInternal(clearState = true)
    }

    fun seekTo(positionMs: Long) = handler.post {
        idleSinkScheduler.cancel()

        val decoder = currentDecoder ?: return@post
        val currentSink = sink
        val seekSucceeded = if (currentSink is LibusbOutputSink) {
            // The libusb sink owns its own native decoder; delegate the seek to it
            // so the C++ pump thread seeks the native decoder and restarts cleanly.
            currentSink.seekTo(positionMs)
        } else {
            runCatching {
                decoder.seekTo(positionMs).also { succeeded ->
                    if (succeeded) {
                        sink?.flush()
                        dopSpillState = DoPSpillState(ByteArray(4), 0)
                        // Clear the SUE filter graph state (IIR history, resampler delay)
                        // so no pre-seek samples survive into the new position.
                        activeSueStage?.reset()
                    }
                }
            }.onFailure { Log.e(TAG, "seekTo failed: ${it.message}") }
                .getOrDefault(false)
        }
        if (!seekSucceeded) {
            Log.e(TAG, "seekTo rejected target=${positionMs}ms; retaining current position")
            return@post
        }

        playStartPositionMs = positionMs.coerceAtLeast(0L)
        // Libusb sinks expose an absolute frame epoch after seek. Capturing it
        // here makes the engine calculate target + (head - seekHead), rather
        // than adding the absolute target twice.
        sinkStartFrames = if (currentSink is LibusbOutputSink) {
            currentSink.getPlaybackHeadPositionFrames()
        } else {
            0L
        }
        _positionMs.value = playStartPositionMs

        if (playWhenReady) {
            sink?.play()
            writeLoopRunning = false
            startWriteLoopIfNeeded()
        }
        notifyEngineStateChanged()
    }

    fun release() = handler.post {
        idleSinkScheduler.cancel()
        doStopInternal(clearState = true)
        // Clear the bit-perfect AudioMixerAttributes preference so AudioFlinger
        // is not left holding a stale routing constraint after the engine closes.
        usbAudioSinkFactory.releasePlatformBitPerfectRouting()
        audioThread.quitSafely()
    }

    /**
     * Reloads the current track from the current playhead position using the
     * latest persisted settings (e.g. after the SUE or Hi-Res Remaster toggle
     * changes).
     *
     * The method is a no-op when no track is loaded. The [_pathReport] update
     * propagates to the telemetry collector so the telemetry dialog reflects the
     * new enhancement state immediately.
     */
    fun reloadWithCurrentSettings() = handler.post {
        val uri = _currentUri.value ?: return@post
        val position = snapshotCurrentPlaybackPositionMs()
        // Preserve play-when-ready so toggling a setting mid-playback resumes seamlessly.
        val wasPlaying = playWhenReady || _state.value == EnginePlaybackState.PLAYING
        idleSinkScheduler.cancel()
        Log.i(TAG, "Reloading track with updated settings: uri=$uri pos=${position}ms play=$wasPlaying")
        doLoadTrack(uri, position, autoPlay = wasPlaying)
    }

    /**
     * Returns `true` when the currently loaded PCM path relies on the internal
     * SUE / Hi-Res remaster resampler path.
     *
     * Used by playback-engine settings coordinators to avoid redundant reloads.
     */
    fun isStandaloneSoxSuppressedForCurrentTrack(): Boolean {
        val format = _currentFormat.value ?: return false
        if (format.isDsd) return true
        return shouldUseSueStage(
            format = format,
            sueEnabled = isSueEnabled(),
            hiResEnabled = isHiResRemasterEnabled(),
        )
    }

    /**
     * Immediately releases the USB output sink when audio focus is permanently
     * lost to another app.
     *
     * The [currentDecoder], [_currentFormat], and [_positionMs] are preserved so
     * the next [play] transparently rebuilds the sink at the exact pause point.
     *
     * Must only be called after the engine has already been paused.
     */
    fun releaseUsbSinkNow() = handler.post {
        idleSinkScheduler.cancel()
        releaseIdleSinkInternal()
        // Closing either a direct libusb sink or an AudioTrack is not enough on
        // Android 14+: a preferred BIT_PERFECT mixer profile can outlive the
        // stream. Clear it on every immediate release so other applications can
        // reopen the same DAC through AudioFlinger without a cable re-plug.
        usbAudioSinkFactory.releasePlatformBitPerfectRouting()
    }

    /**
     * Synchronously stops playback and releases the claimed USB DAC interface.
     *
     * Clears the bit-perfect [android.media.AudioMixerAttributes] preference so
     * AudioFlinger does not hold a stale routing constraint while the engine
     * manager hot-swaps to a different engine strategy.
     */
    suspend fun stopAndReleaseOutput() {
        suspendCancellableCoroutine { continuation ->
            handler.post {
                idleSinkScheduler.cancel()
                doStopInternal(clearState = true)
                usbAudioSinkFactory.releasePlatformBitPerfectRouting()
                if (continuation.isActive) {
                    continuation.resume(Unit)
                }
            }
        }
    }

    // ── Audio-thread implementation ──────────────────────────────────────────

    private fun doLoadTrack(uri: String, startPositionMs: Long, autoPlay: Boolean) {
        BitPerfectDiagnosticsLogger.onLoadStart(uri, autoPlay, startPositionMs)
        doStopInternal(clearState = false, releaseWakeLock = !autoPlay)
        _state.value = EnginePlaybackState.LOADING
        notifyEngineStateChanged()

        val path = runCatching { resolveUriToPath(context, uri) }.getOrNull()
        if (path == null) {
            reportPlaybackError(
                logMessage = "Cannot resolve URI to a file path: $uri",
                userMessage = "Unable to open '$uri' — file not accessible",
            )
            BitPerfectDiagnosticsLogger.onLoadComplete(autoPlay, success = false, error = "URI resolution failed")
            return
        }

        // Settings are resolved once per load so the value is immutable for
        // the loaded track's lifetime.
        val sueEnabled = isSueEnabled()
        val hiResEnabled = isHiResRemasterEnabled()

        // Tiered session building: T1 (bit-perfect) → T3 (PCM fallback).
        val session = sessionLoader.tryLoadBitPerfectSession(path, sueEnabled, hiResEnabled)
            ?: sessionLoader.tryLoadPcmFallbackSession(path, sueEnabled, hiResEnabled) { logMsg, userMsg, t ->
                reportPlaybackError(logMessage = logMsg, userMessage = userMsg, throwable = t)
            }
        if (session == null) {
            BitPerfectDiagnosticsLogger.onLoadComplete(autoPlay, success = false, error = "all tiers exhausted")
            return  // error already reported inside tryLoadPcmFallbackSession
        }

        val (decoder, format, newSink, dsdPlaybackContext, newSueStage, pathType, decision, activeOutputThreadSampleRateHz) = session
        if (dsdPlaybackContext != null) {
            dopSpillState = DoPSpillState(ByteArray(4), 0)
        }

        if (startPositionMs > 0L) {
            // LibusbOutputSink manages its own native decoder; the outer
            // FFmpegDecoder is only used for format detection on the libusb path.
            if (newSink is LibusbOutputSink) {
                newSink.seekTo(startPositionMs)
            } else {
                runCatching { decoder.seekTo(startPositionMs) }
            }
        }

        transportBuffers.ensureFor(format, dsdPlaybackContext)

        val sueInfo = buildSueInfo(format, newSueStage, sueEnabled, hiResEnabled)

        applyLoadedTrack(
            decoder = decoder,
            sink = newSink,
            format = format,
            uri = uri,
            startPositionMs = startPositionMs,
            pathReport = decoratePathReport(
                report = buildDsdPathReport(newSink.pathReport, dsdPlaybackContext),
                pathType = pathType,
                decision = decision,
                activeOutputThreadSampleRateHz = activeOutputThreadSampleRateHz,
                sueInfo = sueInfo,
            ),
            playbackClockRateHz = pipelinePlaybackClockRate(
                format = format,
                dsdPlaybackContext = dsdPlaybackContext,
                sueStage = newSueStage,
                sinkClockRateHz = newSink.playbackHeadClockRateHz,
            ),
            dsdPlaybackContext = dsdPlaybackContext,
            sueStage = newSueStage,
            pathType = pathType,
            decision = decision,
            activeOutputThreadSampleRateHz = activeOutputThreadSampleRateHz,
        )

        Log.i(
            PATH_TAG,
            "Audiophile load prepared codec=${format.codec.displayName} sampleRate=${format.sampleRateHz}Hz " +
                "channels=${format.channelCount} sink=${newSink.javaClass.simpleName} " +
                "direct=${newSink.pathReport.usedDirectFlag} routedDevice=${newSink.pathReport.routedDeviceName ?: "unknown"} " +
                " pathType=$pathType decision=$decision threadHz=$activeOutputThreadSampleRateHz " +
                " sue=${newSueStage?.isActive == true}" +
                (if (newSueStage?.isActive == true) " (${format.sampleRateHz}→${newSueStage.outputSampleRateHz}Hz ${format.codec.displayName}/${newSueStage.bitrateKbps}kbps)" else "") +
                " autoPlay=$autoPlay startPositionMs=$startPositionMs"
        )

        if (autoPlay) {
            runCatching {
                wakeLockController.withRollback {
                    newSink.play()
                    wakeLockController.acquire()
                    playWhenReady = true
                    _state.value = EnginePlaybackState.PLAYING
                    startWriteLoopIfNeeded()
                    startPositionTicker()
                }
            }.onFailure { throwable ->
                playWhenReady = false
                releaseCurrentPlaybackResources()
                clearPlaybackSnapshot()
                BitPerfectDiagnosticsLogger.onLoadComplete(autoPlay, success = false, error = throwable.message)
                reportPlaybackError(
                    logMessage = "Autoplay start failed: ${throwable.message}",
                    userMessage = "Audio playback could not be started: ${throwable.message}",
                    throwable = throwable,
                )
                return
            }
            BitPerfectDiagnosticsLogger.onLoadComplete(autoPlay, success = true)
        } else {
            playWhenReady = false
            wakeLockController.release()
            _state.value = EnginePlaybackState.READY
            BitPerfectDiagnosticsLogger.onLoadComplete(autoPlay, success = true)
        }
        notifyEngineStateChanged()
    }

    // ── Gapless ──────────────────────────────────────────────────────────────

    private fun doEnqueueNext(uri: String) {
        gaplessQueue.clear()

        // Every early exit below MUST leave at least a URI-only entry behind.
        // A silently empty queue at EOF ends playback mid-queue with the app in
        // background — the URI-only downgrade defers the load to EOF, where
        // doLoadTrack retries with full error reporting.
        val current = _currentFormat.value ?: run {
            BitPerfectDiagnosticsLogger.onEnqueueDowngradedToUriOnly("no-current-format")
            gaplessQueue.enqueueUriOnly(uri)
            return
        }

        val path = runCatching { resolveUriToPath(context, uri) }.getOrNull()
        if (path == null) {
            BitPerfectDiagnosticsLogger.onEnqueueDowngradedToUriOnly("uri-resolution-failed")
            gaplessQueue.enqueueUriOnly(uri)
            return
        }

        val preload = FFmpegDecoder()
        val tierOneFmt = runCatching { preload.open(path, forcePcm = false) }.getOrNull()
        val fmt = if (tierOneFmt != null && canUseBitPerfectForGapless(tierOneFmt)) {
            tierOneFmt
        } else {
            preload.close()
            val reopened = FFmpegDecoder()
            val pcmFmt = runCatching { reopened.open(path, forcePcm = true) }.getOrElse {
                reopened.close()
                BitPerfectDiagnosticsLogger.onEnqueueDowngradedToUriOnly("pcm-preload-open-failed")
                gaplessQueue.enqueueUriOnly(uri)
                return
            }
            if (!areGaplessPipelinesCompatible(current, pcmFmt)) {
                reopened.close()
                gaplessQueue.enqueueUriOnly(uri)
                return
            }
            gaplessQueue.enqueueCompatible(reopened, pcmFmt, uri)
            return
        }

        if (!areGaplessPipelinesCompatible(current, fmt)) {
            preload.close()
            gaplessQueue.enqueueUriOnly(uri)
            return
        }
        gaplessQueue.enqueueCompatible(preload, fmt, uri)
    }

    /**
     * Returns `true` when [format] can plausibly use the bit-perfect transport
     * for gapless preload — i.e. either non-DSD, or a DSD track for which the
     * direct USB path + DAC profile resolve a valid native DSD / DoP context.
     */
    private fun canUseBitPerfectForGapless(format: AudioFormatInfo): Boolean {
        if (!format.isDsd) return true
        return sinkRouter.buildDsdPlaybackContext(format) != null
    }

    /**
     * Ensures a gapless swap only reuses the sink when both tracks produce the
     * same downstream carrier format (raw PCM vs float PCM via SUE or force-48k).
     */
    private fun areGaplessPipelinesCompatible(current: AudioFormatInfo, next: AudioFormatInfo): Boolean {
        if (!BitPerfectGaplessQueue.areGaplessCompatible(current, next)) return false

        val currentUsesFloatCarrier = activeSueStage?.isActive == true
        val sueEnabled = isSueEnabled()
        val hiResEnabled = isHiResRemasterEnabled()
        // ✅ CHANGED: signature now passes pre-read settings to avoid re-reading SharedPreferences
        // twice for the same eligibility check.
        val nextUsesFloatCarrier = ratePolicy.willTrackUseSueOrForce48kStage(next, sueEnabled, hiResEnabled)
        return currentUsesFloatCarrier == nextUsesFloatCarrier
    }

    // ── PCM / DSD chunk processing ───────────────────────────────────────────

    /**
     * Prepares one decoded PCM chunk for sink writing through the SUE stage.
     *
     * Returns `null` when the stage is still warming up and no bytes should be
     * written to the sink yet.
     *
     * @param format Decoded source format for the current track.
     * @param read   Number of valid bytes in [BitPerfectTransportBuffers.pcmBuffer].
     * @return A [PcmWritePayload] ready for [AudiophileOutputSink.write], or `null`.
     */
    private fun preparePcmWritePayload(
        format: AudioFormatInfo,
        read: Int,
    ): PcmWritePayload? {
        transportBuffers.pcmBuffer.position(0)
        transportBuffers.pcmBuffer.limit(read)

        var sourceBuffer = transportBuffers.pcmBuffer
        val sourceFrames = read / format.bytesPerFrame

        val sueStage = activeSueStage
        if (sueStage != null && sueStage.isActive) {
            when (val sueFrames = sueStage.process(sourceBuffer, sourceFrames)) {
                in 1..Int.MAX_VALUE -> {
                    return PcmWritePayload(
                        buffer = sueStage.outputBuffer,
                        bytesToWrite = sueStage.outputBuffer.limit(),
                    )
                }
                0 -> return null
                else -> {
                    Log.w(TAG, "SUE stage error (result=$sueFrames) — bypassing enhancement for this chunk")
                    sourceBuffer = transportBuffers.pcmBuffer
                }
            }
        }

        return PcmWritePayload(
            buffer = sourceBuffer,
            bytesToWrite = sourceBuffer.limit(),
        )
    }

    /** Drains the active enhancement stage tail at end-of-stream. */
    private fun drainEnhancementStagesAtEndOfStream() {
        val sueStage = activeSueStage
        if (sueStage != null && sueStage.isActive) {
            val sueTailFrames = sueStage.flush()
            if (sueTailFrames > 0) {
                runCatching { sink?.write(sueStage.outputBuffer, sueStage.outputBuffer.limit()) }
            }
        }
    }

    /**
     * Rebuilds the per-track SUE diagnostics during a true gapless swap without
     * rebuilding the audio sink.
     *
     * @param format      Decoded format of the incoming gapless track.
     * @param replayGainDb ReplayGain from the incoming track's tags.
     */
    private fun refreshGaplessStages(format: AudioFormatInfo, replayGainDb: Float = REPLAYGAIN_NEUTRAL_DB) {
        val sueEnabled = isSueEnabled()
        val hiResEnabled = isHiResRemasterEnabled()
        val ratePlan = ratePolicy.resolvePcmSampleRatePlan(
            format = format,
            sueEnabled = sueEnabled,
            hiResEnabled = hiResEnabled,
        )
        runCatching { activeSueStage?.close() }
        activeSueStage = buildSueStageIfNeeded(
            format = format,
            sueEnabled = sueEnabled,
            hiResEnabled = hiResEnabled,
            desiredOutputSampleRateHz = ratePlan.sueOutputRateHz,
            replayGainDb = replayGainDb,
            standaloneResampleTargetRateHz = ratePlan.standaloneResampleTargetRateHz,
        )
        _pathReport.value = decoratePathReport(
            report = buildDsdPathReport(sink?.pathReport ?: return, dsdPlaybackContext = null),
            pathType = ratePlan.pathType,
            decision = ratePlan.decision,
            activeOutputThreadSampleRateHz = ratePlan.activeOutputThreadSampleRateHz,
            sueInfo = buildSueInfo(format, activeSueStage, sueEnabled, isHiResRemasterEnabled()),
        )
    }

    /** Prepared sink write payload for one PCM chunk. */
    private data class PcmWritePayload(
        val buffer: ByteBuffer,
        val bytesToWrite: Int,
    )

    // ── Write loop ───────────────────────────────────────────────────────────

    private fun startWriteLoopIfNeeded() {
        if (writeLoopRunning) return
        writeLoopRunning = true
        handler.post(writeLoopRunnable)
    }

    /**
     * Single iteration of the write loop. Posts itself back to the handler so
     * the audio thread's message queue remains responsive to control events
     * (pause / seek / stop) interleaved with sample writes.
     */
    private fun writeLoopIteration() {
        if (!writeLoopRunning) return
        if (!playWhenReady) {
            writeLoopRunning = false
            return
        }
        val decoder = currentDecoder ?: run { writeLoopRunning = false; return }
        val currentSink = sink ?: run { writeLoopRunning = false; return }

        // LibusbOutputSink drives all data delivery through its native pump thread.
        // The Kotlin write loop only polls for end-of-stream.
        if (currentSink is LibusbOutputSink) {
            if (currentSink.eofFlag.get()) {
                onCurrentTrackEnded()
            } else {
                handler.postDelayed(writeLoopRunnable, LIBUSB_EOF_POLL_INTERVAL_MS)
            }
            return
        }

        val dsdPlaybackContext = activeDsdPlaybackContext

        val read = if (dsdPlaybackContext != null) {
            decoder.readNextDsdBuffer(transportBuffers.dsdBuffer)
        } else {
            decoder.readNextBuffer(transportBuffers.pcmBuffer)
        }
        when {
            read > 0 -> {
                var writeBuffer = transportBuffers.pcmBuffer
                val bytesToWrite: Int

                if (dsdPlaybackContext != null) {
                    val preparation = prepareDsdTransportBuffer(
                        dsdPlaybackContext,
                        transportBuffers.dsdBuffer,
                        read,
                        transportBuffers.pcmBuffer,
                        dopSpillState,
                    )
                    dopSpillState = preparation.spillState
                    bytesToWrite = preparation.bytesToWrite
                } else {
                    val format = _currentFormat.value ?: run { writeLoopRunning = false; return }
                    val payload = preparePcmWritePayload(format, read)
                    if (payload == null) {
                        handler.post(writeLoopRunnable)
                        return
                    }
                    writeBuffer = payload.buffer
                    bytesToWrite = payload.bytesToWrite
                }
                if (bytesToWrite <= 0) {
                    handler.post(writeLoopRunnable)
                    return
                }
                val written = try {
                    currentSink.write(writeBuffer, bytesToWrite)
                } catch (exception: Exception) {
                    if (recoverFromDirectUsbWriteFailure(
                            failedSink = currentSink,
                            failureReason = exception.message ?: exception::class.java.simpleName,
                            throwable = exception,
                        )
                    ) {
                        handler.post(writeLoopRunnable)
                        return
                    }
                    reportPlaybackError(
                        logMessage = "Audiophile sink write failed: ${exception.message}",
                        userMessage = "Audio output write failed: ${exception.message}",
                        throwable = exception,
                    )
                    writeLoopRunning = false
                    return
                }
                if (written < 0) {
                    if (recoverFromDirectUsbWriteFailure(
                            failedSink = currentSink,
                            failureReason = "code=$written",
                        )
                    ) {
                        handler.post(writeLoopRunnable)
                        return
                    }
                    reportPlaybackError(
                        logMessage = "Audiophile sink write error: $written — stopping engine",
                        userMessage = "Audio output write failure (code=$written)",
                    )
                    writeLoopRunning = false
                    return
                }
                handler.post(writeLoopRunnable)
            }
            read == FFmpegDecoder.READ_EOF -> onCurrentTrackEnded()
            else -> {
                // READ_RECOVERABLE_ERROR — retry on the next tick.
                handler.postDelayed(writeLoopRecoveryRunnable, RECOVERABLE_ERROR_RETRY_DELAY_MS)
            }
        }
    }

    // ── Track-end and stop ───────────────────────────────────────────────────

    /**
     * Handles end-of-stream for the current track.
     *
     * - If a format-compatible [BitPerfectGaplessQueue.hasCompatiblePreload] entry
     *   is available, swaps it in without touching the sink (true gapless).
     * - If only a URI was enqueued at a different format, loads it with a brief
     *   transition gap.
     * - Otherwise transitions to [EnginePlaybackState.ENDED].
     */
    private fun onCurrentTrackEnded() {
        // Flush the SUE tail samples before checking the gapless queue.
        drainEnhancementStagesAtEndOfStream()

        val gaplessEntry = gaplessQueue.consumeCompatibleEntry()

        BitPerfectDiagnosticsLogger.onTrackEof(
            hasCompatiblePreload = gaplessEntry != null,
            hasPendingUri = gaplessQueue.hasPendingUri,
            currentTrackUri = _currentUri.value,
        )

        if (gaplessEntry != null) {
            // True gapless path — reuse the sink, swap only the decoder.
            currentDecoder?.close()
            currentDecoder = gaplessEntry.decoder
            // Rebuild the SUE stage and publish the new path report BEFORE the
            // new format is emitted: the telemetry collector combines the two
            // flows, and emitting the format first pairs the incoming track's
            // source depth with the outgoing track's report while the lavfi
            // graph rebuilds — the UI then flashes the raw source bit depth
            // (e.g. 16 bit) before jumping to the enhanced USB carrier (32 bit).
            refreshGaplessStages(
                format = gaplessEntry.format,
                replayGainDb = resolveReplayGainDbIfNeeded(
                    decoder = gaplessEntry.decoder,
                    format = gaplessEntry.format,
                    hiResEnabled = isHiResRemasterEnabled(),
                ),
            )
            _currentFormat.value = gaplessEntry.format
            _currentUri.value = gaplessEntry.uri
            _durationMs.value = gaplessEntry.format.durationMs
            playStartPositionMs = 0L
            sinkStartFrames = sink?.getPlaybackHeadPositionFrames() ?: 0L
            _positionMs.value = 0L
            engineListener?.onTrackAdvanced()
            notifyEngineStateChanged()
            handler.post(writeLoopRunnable)
            return
        }

        // A URI-only entry is the normal non-gapless path. When even that is
        // missing (a lost/failed enqueueNext), re-ask the listener before
        // ending: the engine manager caches the follower the playlist adapter
        // last requested, so a dropped preload never silently ends the queue.
        val nextUri = gaplessQueue.consumePendingUri() ?: requestFallbackFollowerUri()
        if (nextUri != null) {
            // Non-gapless enqueued track (format mismatch). Load with an audible transition.
            // ORDERING FIX: run doLoadTrack to completion BEFORE notifying the listener so
            // the adapter index and engine state advance atomically.
            BitPerfectDiagnosticsLogger.onSequentialTransitionCheckpoint("load-start")
            doLoadTrack(nextUri, 0L, autoPlay = true)
            if (_state.value == EnginePlaybackState.ERROR) {
                // One immediate retry: EOF-time loads run while backgrounded, where
                // transient ContentResolver / USB-renegotiation failures are more
                // likely. A persistent failure still surfaces as ERROR (and is now
                // recoverable from the play button via the adapter reload path).
                BitPerfectDiagnosticsLogger.onSequentialLoadRetry(attempt = 1)
                doLoadTrack(nextUri, 0L, autoPlay = true)
            }
            BitPerfectDiagnosticsLogger.onSequentialTransitionCheckpoint("load-done")
            BitPerfectDiagnosticsLogger.onSequentialTransitionCheckpoint("notify-after-load")
            engineListener?.onTrackAdvanced()
            return
        }

        endPlaybackAtEndOfQueue()
    }

    /**
     * Asks the listener for the follower URI after the engine's own queue came
     * up empty at EOF.
     *
     * A fallback equal to the track that just ended is rejected: it is either a
     * stale cache entry (the adapter has not re-scheduled the preload yet) or a
     * repeat-one whose preload was lost — replaying it from here risks an
     * unintended same-track loop, so the engine ends cleanly instead and the
     * (now recoverable) play button restarts it.
     */
    private fun requestFallbackFollowerUri(): String? {
        val fallbackUri = engineListener?.onNextTrackUriRequested()
        val recovered = fallbackUri != null && fallbackUri != _currentUri.value
        BitPerfectDiagnosticsLogger.onEofFallbackUriRequested(recovered)
        return if (recovered) fallbackUri else null
    }

    /** Terminal EOF transition when no follower exists: park the engine in ENDED. */
    private fun endPlaybackAtEndOfQueue() {
        writeLoopRunning = false
        playWhenReady = false
        wakeLockController.release()
        _state.value = EnginePlaybackState.ENDED
        notifyEngineStateChanged()
        stopPositionTicker()
    }

    private fun doStopInternal(clearState: Boolean, releaseWakeLock: Boolean = true) {
        try {
            writeLoopRunning = false
            playWhenReady = false
            stopPositionTicker()
            releaseCurrentPlaybackResources()
            gaplessQueue.clear()
            if (clearState) {
                clearPlaybackSnapshot()
                _state.value = EnginePlaybackState.IDLE
                notifyEngineStateChanged()
            }
        } finally {
            if (releaseWakeLock) {
                wakeLockController.release()
            }
        }
    }

    // ── Idle-sink release ────────────────────────────────────────────────────

    /**
     * Releases the output sink after an extended pause, allowing the OS to
     * reclaim audio hardware and audio focus.
     *
     * The [currentDecoder], [_currentFormat], and [_positionMs] are preserved so
     * [play] can transparently rebuild the sink and seek back to the exact pause
     * point without requiring a full track reload.
     *
     * Invoked on the audio thread via [BitPerfectIdleSinkReleaseScheduler].
     */
    private fun releaseIdleSinkInternal() {
        if (playWhenReady || _state.value != EnginePlaybackState.PAUSED) return
        if (sink == null) return

        Log.i(TAG, "Releasing idle audio sink after ${PAUSED_IDLE_SINK_RELEASE_MS / 60_000L}min pause")
        runCatching { sink?.stop() }
        runCatching { sink?.close() }
        sink = null
        // Intentionally do NOT clear _pathReport here. The path report describes
        // the routing configuration negotiated for this track. That configuration
        // remains valid while paused — the same DAC is still connected. Nulling
        // the report causes AudioTelemetryCollector to lose bit-perfect / direct
        // status until the sink is rebuilt, which the user sees as blank telemetry.
        notifyEngineStateChanged()
    }

    // ── Position ticker ──────────────────────────────────────────────────────

    private fun startPositionTicker() {
        positionTickerJob = appScope.restartTicker(
            currentJob = positionTickerJob,
            intervalMs = POSITION_TICK_INTERVAL_MS,
        ) {
            handler.post {
                val currentSink = sink ?: return@post
                _positionMs.value = calculateBitPerfectPlaybackPositionMs(
                    playStartPositionMs = playStartPositionMs,
                    sinkStartFrames = sinkStartFrames,
                    playbackHeadFrames = currentSink.getPlaybackHeadPositionFrames(),
                    sampleRateHz = playbackClockRateHz,
                )
                // Invalidate the Media3 state snapshot on every tick so the system
                // notification seekbar and transport controls reflect the current position.
                notifyEngineStateChanged()

                // Routing heartbeat — every ~10 s while playing.
                routingHeartbeatTick++
                if (routingHeartbeatTick >= ROUTING_HEARTBEAT_TICKS) {
                    routingHeartbeatTick = 0
                    _currentFormat.value?.let { fmt ->
                        Log.i(
                            ROUTING_TAG,
                            "[heartbeat] ${BitPerfectRoutingDiagnostics.describeRoutingShort(currentSink, fmt)}" +
                                " pos=${_positionMs.value}ms"
                        )
                    }
                }
            }
        }
    }

    private fun stopPositionTicker() {
        positionTickerJob = positionTickerJob.cancelAndClear()
    }

    /**
     * Captures the current sink playhead into the engine's public position state.
     *
     * Runtime routing and DSP reloads call this synchronously on the audio thread
     * so they preserve the real playhead rather than the last periodic UI tick.
     */
    private fun snapshotCurrentPlaybackPositionMs(): Long {
        val currentSink = sink ?: return _positionMs.value
        val positionMs = calculateBitPerfectPlaybackPositionMs(
            playStartPositionMs = playStartPositionMs,
            sinkStartFrames = sinkStartFrames,
            playbackHeadFrames = currentSink.getPlaybackHeadPositionFrames(),
            sampleRateHz = playbackClockRateHz,
        ).let { position ->
            val durationMs = _durationMs.value
            if (durationMs > 0L) position.coerceAtMost(durationMs) else position
        }
        _positionMs.value = positionMs
        return positionMs
    }

    // ── Engine state notification ────────────────────────────────────────────

    private fun notifyEngineStateChanged() {
        engineListener?.onEngineStateChanged()
    }

    // ── Track application ────────────────────────────────────────────────────

    private fun applyLoadedTrack(
        decoder: FFmpegDecoder,
        sink: AudiophileOutputSink,
        format: AudioFormatInfo,
        uri: String,
        startPositionMs: Long,
        pathReport: PipelinePathReport,
        playbackClockRateHz: Int,
        dsdPlaybackContext: DsdPlaybackContext?,
        sueStage: SueStage?,
        pathType: PathType,
        decision: Decision,
        activeOutputThreadSampleRateHz: Int,
    ) {
        currentDecoder = decoder
        this.sink = sink
        activeSueStage = sueStage
        currentPathType = pathType
        currentRateDecision = decision
        currentActiveOutputThreadSampleRateHz = activeOutputThreadSampleRateHz
        // Report before format: the telemetry collector combines the two flows,
        // and the reverse order pairs the new track's source depth with the old
        // track's report for one emission — visible as a bit-depth flash.
        _pathReport.value = pathReport
        _currentFormat.value = format
        _currentUri.value = uri
        _durationMs.value = format.durationMs
        playStartPositionMs = startPositionMs.coerceAtLeast(0L)
        // A pre-seeked libusb sink reports an absolute playhead at the requested
        // position before playback starts. Treat that value as the local epoch
        // instead of adding it to startPositionMs a second time.
        sinkStartFrames = sink.getPlaybackHeadPositionFrames()
        this.playbackClockRateHz = playbackClockRateHz
        _positionMs.value = playStartPositionMs
        activeDsdPlaybackContext = dsdPlaybackContext
        dopSpillState = DoPSpillState(ByteArray(4), 0)
        routingHeartbeatTick = 0
        // Log the routing banner once at track-load time so the audio path is
        // visible in logcat even before the user presses play.
        BitPerfectRoutingDiagnostics.logRoutingBanner(sink, format, trigger = "load")
    }

    // ── USB fallback recovery ────────────────────────────────────────────────

    /**
     * Recovers from a failing custom USB host sink by rebuilding output on the
     * platform [com.androidexpert35.audiophilemusicplayer.data.playback.native_.AudioTrackSink] path.
     *
     * @param failedSink Sink instance that just failed a write.
     * @param failureReason Human-readable write failure details for diagnostics.
     * @param throwable Optional underlying failure cause.
     * @return `true` when playback was migrated to the platform sink and can
     *   continue, `false` when the failure should still surface as an error.
     */
    private fun recoverFromDirectUsbWriteFailure(
        failedSink: AudiophileOutputSink,
        failureReason: String,
        throwable: Throwable? = null,
    ): Boolean {
        val isRecoverableUsbSink = failedSink is com.androidexpert35.audiophilemusicplayer.data.playback.usb.UsbAudioSink ||
            failedSink is com.androidexpert35.audiophilemusicplayer.data.playback.usb.LibusbPcmEnhancedSink
        if (!isRecoverableUsbSink) return false
        val decoder = currentDecoder ?: return false
        val format = _currentFormat.value ?: return false
        if (format.isDsd) return false

        val resumePositionMs = calculateBitPerfectPlaybackPositionMs(
            playStartPositionMs = playStartPositionMs,
            sinkStartFrames = sinkStartFrames,
            playbackHeadFrames = failedSink.getPlaybackHeadPositionFrames(),
            sampleRateHz = playbackClockRateHz,
        )

        runCatching { failedSink.stop() }
        runCatching { failedSink.close() }

        // When SUE is active the sink was opened at float32 PCM at the SUE output rate.
        // The platform fallback must match that format.
        val sinkFormat = pipelineOutputFormat(format, activeSueStage)

        val fallbackSink = runCatching {
            usbAudioSinkFactory.createPlatformFallback(sinkFormat)
        }.getOrElse { fallbackError ->
            Log.e(TAG, "Direct USB sink fallback creation failed after write error: $failureReason", fallbackError)
            return false
        }

        val seekResult = runCatching { decoder.seekTo(resumePositionMs) }
        if (seekResult.isFailure) {
            runCatching { fallbackSink.close() }
            Log.e(
                TAG,
                "Direct USB sink fallback seek failed at ${resumePositionMs}ms after write error: $failureReason",
                seekResult.exceptionOrNull(),
            )
            return false
        }

        val playResult = runCatching { fallbackSink.play() }
        if (playResult.isFailure) {
            runCatching { fallbackSink.close() }
            Log.e(TAG, "Direct USB sink fallback could not start after write error: $failureReason", playResult.exceptionOrNull())
            return false
        }

        sink = fallbackSink
        currentPathType = PathType.STANDARD_PCM
        currentRateDecision = Decision.Bypass
        currentActiveOutputThreadSampleRateHz = 0
        _pathReport.value = decorateCurrentPathReport(
            report = fallbackSink.pathReport,
            sueInfo = buildSueInfo(
                format = format,
                sueStage = activeSueStage,
                sueEnabled = isSueEnabled(),
                hiResEnabled = isHiResRemasterEnabled(),
            ),
        )
        playStartPositionMs = resumePositionMs
        sinkStartFrames = 0L
        _positionMs.value = resumePositionMs
        playbackClockRateHz = pipelinePlaybackClockRate(
            format = format,
            dsdPlaybackContext = null,
            sueStage = activeSueStage,
            sinkClockRateHz = fallbackSink.playbackHeadClockRateHz,
        )
        Log.w(PATH_TAG, "Direct USB sink write failed ($failureReason); continuing on platform AudioTrack fallback at ${resumePositionMs}ms", throwable)
        notifyEngineStateChanged()
        return true
    }

    // ── Resource lifecycle ───────────────────────────────────────────────────

    private fun releaseCurrentPlaybackResources() {
        runCatching { sink?.stop() }
        runCatching { sink?.close() }
        sink = null
        runCatching { currentDecoder?.close() }
        currentDecoder = null
        runCatching { activeSueStage?.close() }
        activeSueStage = null
        activeDsdPlaybackContext = null
        currentPathType = PathType.STANDARD_PCM
        currentRateDecision = Decision.Bypass
        currentActiveOutputThreadSampleRateHz = 0
        dopSpillState = DoPSpillState(ByteArray(4), 0)
        playbackClockRateHz = 0
    }

    private fun clearPlaybackSnapshot() {
        _currentFormat.value = null
        _currentUri.value = null
        _pathReport.value = null
        _durationMs.value = 0L
        _positionMs.value = 0L
        currentPathType = PathType.STANDARD_PCM
        currentRateDecision = Decision.Bypass
        currentActiveOutputThreadSampleRateHz = 0
    }

    /**
     * Applies the current session's path classification and rate decision to a
     * freshly built sink report.
     */
    private fun decorateCurrentPathReport(
        report: PipelinePathReport,
        sueInfo: SueInfo?,
    ): PipelinePathReport = decoratePathReport(
        report = report,
        pathType = currentPathType,
        decision = currentRateDecision,
        activeOutputThreadSampleRateHz = currentActiveOutputThreadSampleRateHz,
        sueInfo = sueInfo,
    )

    /**
     * Applies resolved path metadata to [report] so telemetry and logging can
     * describe both the transport path and the standard-PCM rate policy.
     */
    private fun decoratePathReport(
        report: PipelinePathReport,
        pathType: PathType,
        decision: Decision,
        activeOutputThreadSampleRateHz: Int,
        sueInfo: SueInfo?,
    ): PipelinePathReport = report.copy(
        activeOutputThreadSampleRateHz = activeOutputThreadSampleRateHz,
        pathType = pathType,
        decision = decision,
        sueInfo = sueInfo,
    )

    // ── Playback settings helpers ─────────────────────────────────────────────

    /**
     * Reads the SUE enabled preference from SharedPreferences.
     *
     * Called once per [doLoadTrack] on the audio thread so the resolved value is
     * immutable for the loaded track's lifetime.
     */
    private fun isSueEnabled(): Boolean =
        context.getSharedPreferences(SettingsPreferences.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(
                SettingsPreferences.KEY_SUE_ENABLED,
                SettingsPreferences.DEFAULT_SUE_ENABLED,
            )

    /**
     * Reads the "Hi-Res Dynamic Remaster enabled" preference once per track load.
     *
     * Called on the audio thread so the value is immutable for the loaded
     * track's lifetime.
     */
    private fun isHiResRemasterEnabled(): Boolean =
        context.getSharedPreferences(SettingsPreferences.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(
                SettingsPreferences.KEY_HIRES_REMASTER_ENABLED,
                SettingsPreferences.DEFAULT_HIRES_REMASTER_ENABLED,
            )

    // ── Error reporting ──────────────────────────────────────────────────────

    private fun reportPlaybackError(
        logMessage: String,
        userMessage: String,
        throwable: Throwable? = null,
    ) {
        try {
            if (throwable == null) Log.e(TAG, logMessage) else Log.e(TAG, logMessage, throwable)
            idleSinkScheduler.cancel()
            writeLoopRunning = false
            playWhenReady = false
            stopPositionTicker()
            lastErrorMessage = userMessage
            _state.value = EnginePlaybackState.ERROR
            notifyEngineStateChanged()
        } finally {
            wakeLockController.release()
        }
    }

    private companion object {
        const val TAG = "AudiophileEngine"

        /** Back-off when the decoder reports a recoverable glitch. */
        const val RECOVERABLE_ERROR_RETRY_DELAY_MS = 10L

        /**
         * Poll interval for [LibusbOutputSink] EOF detection.
         *
         * The libusb sink's pump thread signals end-of-stream via an atomic flag;
         * the write loop checks this flag every 50 ms instead of performing a
         * blocking FFmpeg read.
         */
        const val LIBUSB_EOF_POLL_INTERVAL_MS = 50L

        const val PATH_TAG = AUDIPHILE_PATH_TAG

        /**
         * Logcat tag used exclusively for routing-path log lines.
         *
         * Filter with: `adb logcat -s AudiophileRouting`
         */
        const val ROUTING_TAG = "AudiophileRouting"

        /**
         * Number of position-ticker ticks between routing heartbeat log lines.
         *
         * `50 ticks × 200 ms = 10 000 ms` — one heartbeat every 10 seconds.
         */
        const val ROUTING_HEARTBEAT_TICKS = 50

        /**
         * Duration the player must remain in [EnginePlaybackState.PAUSED] before
         * the output sink is proactively released to free audio hardware resources.
         *
         * Two minutes is a conservative threshold: long enough to avoid spurious
         * releases during a brief track-change or UI-navigation pause.
         */
        const val PAUSED_IDLE_SINK_RELEASE_MS = 2L * 60L * 1_000L
    }
}
