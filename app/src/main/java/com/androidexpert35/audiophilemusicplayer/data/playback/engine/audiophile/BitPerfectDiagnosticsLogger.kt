package com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile

import android.util.Log
import com.androidexpert35.audiophilemusicplayer.data.playback.engine.EnginePlaybackState
import com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile.BitPerfectDiagnosticsLogger.onLoadComplete
import com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile.BitPerfectDiagnosticsLogger.onLoadTierResult
import com.androidexpert35.audiophilemusicplayer.data.playback.native_.AudioFormatInfo

/**
 * Structured diagnostic logger for [BitPerfectPlaybackEngine] state-machine events.
 *
 * Each method emits a machine-parseable line prefixed with `[BP]` so all
 * engine diagnostics can be extracted from a logcat dump with a single
 * `grep` filter:
 *
 * ```
 * adb logcat -s AudiophileDiag
 * ```
 *
 * ### Why a dedicated logger?
 *
 * The engine's three observed failure modes produce no log output at the
 * moment of failure:
 *
 * 1. **Silent play() drop** — `if (currentDecoder == null) return@post` emits nothing.
 *    This means a cold-start race or a failed sequential load produces a
 *    completely silent logcat — impossible to diagnose without this logger.
 *
 * 2. **URI resolution failure** — `resolveUriToSource` throws; the catch lands
 *    in `reportPlaybackError`, but no checkpoint before the throw records
 *    which tier or what path was being resolved.
 *
 * 3. **Intermediate state during gapless transition** — `onTrackAdvanced →
 *    advanceMediaItemIndex` fires on the main thread while `doLoadTrack` is
 *    still executing on the audio thread. Without time-stamped checkpoints,
 *    the interleaving of these two threads is invisible.
 *
 * ### Thread safety
 *
 * All methods are safe to call from any thread. The underlying `android.util.Log`
 * API is thread-safe. No mutable state is held in this object.
 */
object BitPerfectDiagnosticsLogger {

    private const val TAG = "AudiophileDiag"

    // ── State transitions ────────────────────────────────────────────────────

    /**
     * Records every [EnginePlaybackState] transition with a wall-clock timestamp.
     *
     * A state change that is never followed by `PLAYING` within a reasonable
     * time window is a strong indicator of a stuck-loading scenario (Case A/B).
     *
     * @param from Previous state.
     * @param to   New state.
     * @param trigger Human-readable description of what caused the transition.
     */
    fun onStateTransition(
        from: EnginePlaybackState,
        to: EnginePlaybackState,
        trigger: String,
    ) {
        Log.i(TAG, "[BP] state: $from → $to  trigger=$trigger  ts=${nowMs()}")
    }

    // ── Load lifecycle ───────────────────────────────────────────────────────

    /**
     * Records the start of a [BitPerfectPlaybackEngine.doLoadTrack] call before
     * URI resolution.
     *
     * If this event appears in logs without a subsequent [onLoadTierResult] or
     * [onLoadComplete], URI resolution threw an exception that was not caught
     * or logged downstream.
     *
     * @param uri      Content URI being loaded (sanitised — no path is emitted).
     * @param autoPlay Whether the load is expected to auto-start playback.
     * @param startMs  Seek target in milliseconds.
     */
    fun onLoadStart(uri: String, autoPlay: Boolean, startMs: Long) {
        // Deliberately does not log the raw URI to avoid emitting user file paths.
        val scheme = uri.substringBefore("://").ifBlank { "unknown" }
        Log.i(TAG, "[BP] load-start: scheme=$scheme autoPlay=$autoPlay startMs=$startMs")
    }

    /**
     * Records the outcome of each tier attempt during [BitPerfectPlaybackEngine.doLoadTrack].
     *
     * Crucially this records a `null` (silent tier failure) separately from a
     * thrown exception so the exact fallback path is visible even when the
     * caller swallows exceptions.
     *
     * @param tier       "T1-bitperfect", "T3-pcm-fallback", etc.
     * @param success    `true` if the tier produced a usable [LoadedAudioSession].
     * @param failReason Why the tier failed, or `null` on success.
     */
    fun onLoadTierResult(tier: String, success: Boolean, failReason: String? = null) {
        if (success) {
            Log.i(TAG, "[BP] load-tier: tier=$tier PASS")
        } else {
            Log.w(TAG, "[BP] load-tier: tier=$tier FAIL reason=${failReason ?: "null-return"}")
        }
    }

    /**
     * Records the fully resolved [AudioFormatInfo] immediately after a
     * successful decoder open.
     *
     * The `bitrateKbps` and `codecName` fields here are the raw values from FFmpeg
     * before any filtering. Comparing this with what the UI shows exposes whether
     * the telemetry mapping step (Case C) or the decoder probe step is at fault.
     *
     * @param format  The [AudioFormatInfo] produced by the decoder.
     * @param source  "T1" or "T3" to identify which tier opened the decoder.
     */
    fun onDecoderOpened(format: AudioFormatInfo, source: String) {
        Log.i(
            TAG,
            "[BP] decoder-open: src=$source codec=${format.codec.displayName} " +
                "codecName=${format.codecName} bitrateKbps=${format.bitrateKbps} " +
                "sampleRateHz=${format.sampleRateHz} channels=${format.channelCount} " +
                "bitDepth=${format.sourceBitDepth} durationMs=${format.durationMs} " +
                "isDsd=${format.isDsd} isResampledDsd=${format.isResampledDsd}"
        )
    }

    /**
     * Records when a [BitPerfectPlaybackEngine.doLoadTrack] concludes with either
     * success or a reported-error outcome.
     *
     * @param autoPlay Whether autoplay was requested.
     * @param success  `true` when the engine reached PLAYING or READY state.
     * @param error    Error message when [success] is `false`.
     */
    fun onLoadComplete(autoPlay: Boolean, success: Boolean, error: String? = null) {
        if (success) {
            Log.i(TAG, "[BP] load-complete: autoPlay=$autoPlay SUCCESS")
        } else {
            Log.e(TAG, "[BP] load-complete: autoPlay=$autoPlay FAILED error=$error")
        }
    }

    // ── Play command guard ───────────────────────────────────────────────────

    /**
     * Records a `play()` call that was silently dropped because `currentDecoder`
     * was null on the audio thread at the time the handler processed the message.
     *
     * **This is the most critical diagnostic event for Case A.** A drop here
     * means `play()` arrived before `doLoadTrack` finished — the calling code
     * must either:
     *  - Use `loadAndPlay` (atomically loads + plays in one handler post), or
     *  - Wait for [EnginePlaybackState.READY] before issuing `play()`.
     *
     * @param engineState The state the engine was in when the drop occurred.
     */
    fun onPlayDroppedNoDecoder(engineState: EnginePlaybackState) {
        Log.w(
            TAG,
            "[BP] play-DROPPED: currentDecoder==null state=$engineState " +
                "— play() arrived before doLoadTrack completed on audio thread. " +
                "Use loadAndPlay() or await READY state before calling play()."
        )
    }

    // ── Sequential playback ──────────────────────────────────────────────────

    /**
     * Records the state of the gapless queue at the moment EOF is received
     * from the decoder.
     *
     * This checkpoint reveals whether the next-track URI had been enqueued
     * at all before EOF fired. A missing enqueue is a scheduling problem
     * in `AudiophileSimpleBasePlayer.enqueueNext`.
     *
     * @param hasCompatiblePreload Whether the queue has a decoder-ready gapless entry.
     * @param hasPendingUri        Whether the queue has a URI-only entry (format unknown).
     * @param currentTrackUri      Sanitised source URI string (scheme only).
     */
    fun onTrackEof(
        hasCompatiblePreload: Boolean,
        hasPendingUri: Boolean,
        currentTrackUri: String?,
    ) {
        val scheme = currentTrackUri?.substringBefore("://")?.ifBlank { "n/a" } ?: "n/a"
        Log.i(
            TAG,
            "[BP] track-EOF: scheme=$scheme " +
                "hasGaplessPreload=$hasCompatiblePreload hasPendingUri=$hasPendingUri"
        )
    }

    /**
     * Records an [BitPerfectPlaybackEngine.doEnqueueNext] preload that could not
     * fully open the next decoder and was downgraded to a URI-only entry.
     *
     * A downgraded enqueue is not a failure — the full load is simply deferred
     * to end-of-stream, where `doLoadTrack` retries with proper error
     * reporting. Before this event existed, these paths returned silently and
     * playback ended mid-queue with no log trace (the background auto-advance
     * failure).
     *
     * @param reason Which precondition failed ("no-current-format",
     *   "uri-resolution-failed", "pcm-preload-open-failed").
     */
    fun onEnqueueDowngradedToUriOnly(reason: String) {
        Log.w(TAG, "[BP] enqueue-downgraded: reason=$reason — full load deferred to EOF")
    }

    /**
     * Records the EOF fallback where the engine's own queue was empty and the
     * follower URI had to be re-fetched from the listener
     * ([AudioPlayerEngine.Listener.onNextTrackUriRequested]).
     *
     * Seeing this event means an [BitPerfectPlaybackEngine.enqueueNext] result
     * was lost entirely (posted but never executed, or cleared by a race) —
     * worth correlating with surrounding state transitions.
     *
     * @param recovered `true` when the listener returned a URI and auto-advance
     *   can proceed; `false` when the queue genuinely has no follower.
     */
    fun onEofFallbackUriRequested(recovered: Boolean) {
        if (recovered) {
            Log.w(TAG, "[BP] eof-fallback: engine queue empty but listener supplied follower URI — advancing")
        } else {
            Log.i(TAG, "[BP] eof-fallback: no follower from listener — genuine end of queue")
        }
    }

    /**
     * Records a retry of the EOF-time sequential load after the first attempt
     * drove the engine into [EnginePlaybackState.ERROR].
     *
     * @param attempt 1-based retry attempt number.
     */
    fun onSequentialLoadRetry(attempt: Int) {
        Log.w(TAG, "[BP] seq-transition: load FAILED — retry attempt=$attempt")
    }

    /**
     * Records the moment [BitPerfectPlaybackEngine] fires [AudioPlayerEngine.Listener.onTrackAdvanced]
     * vs when the next [BitPerfectPlaybackEngine.doLoadTrack] begins.
     *
     * These two events currently fire in this order on the *audio thread*:
     * 1. `engineListener?.onTrackAdvanced()` — trampolines to main thread asynchronously
     * 2. `doLoadTrack(nextUri, autoPlay = true)` — runs synchronously on audio thread
     *
     * If the main thread's `invalidateState()` snapshots the engine mid-load
     * (during `EnginePlaybackState.LOADING`), the `AudiophileSimpleBasePlayer`
     * playlist index is already advanced but the engine hasn't finished loading.
     * This creates a transient state mismatch. Log both sides to correlate.
     *
     * @param event "notify" when `onTrackAdvanced()` fires, "load-start" when
     *   `doLoadTrack` begins, "load-done" when it completes.
     */
    fun onSequentialTransitionCheckpoint(event: String) {
        Log.i(TAG, "[BP] seq-transition: $event  ts=${nowMs()}")
    }

    // ── SUE state ────────────────────────────────────────────────────────────

    /**
     * Records the fully resolved SUE stage parameters, including whether the
     * native context was allocated.
     *
     * Compare this with what `AudioTelemetryCollector.buildSnapshot` emits to
     * confirm that the telemetry pipeline sees the same values as the engine.
     *
     * @param bitrateKbps     Bitrate passed to the native SUE context.
     * @param isActive        Whether the native handle is non-zero.
     * @param profileResolved Resolved intensity profile name.
     * @param failureReason   Init error from JNI if the handle is zero.
     */
    fun onSueStageBuilt(
        bitrateKbps: Int,
        isActive: Boolean,
        profileResolved: String,
        failureReason: String?,
    ) {
        if (isActive) {
            Log.i(
                TAG,
                "[BP] sue-stage: ACTIVE profile=$profileResolved bitrateKbps=$bitrateKbps"
            )
        } else {
            Log.w(
                TAG,
                "[BP] sue-stage: INACTIVE profile=$profileResolved bitrateKbps=$bitrateKbps " +
                    "reason=${failureReason ?: "bypass-or-disabled"}"
            )
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun nowMs(): Long = System.currentTimeMillis()
}

