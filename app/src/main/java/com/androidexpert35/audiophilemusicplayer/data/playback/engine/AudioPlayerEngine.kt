package com.androidexpert35.audiophilemusicplayer.data.playback.engine

import com.androidexpert35.audiophilemusicplayer.data.playback.native_.AudioFormatInfo
import com.androidexpert35.audiophilemusicplayer.data.playback.native_.PipelinePathReport
import kotlinx.coroutines.flow.StateFlow

/**
 * Strategy contract for every audio playback engine in the app.
 *
 * Implementations encapsulate an entire playback stack (FFmpeg/AudioTrack,
 * ExoPlayer, …) behind a uniform surface so higher layers — the Media3
 * [androidx.media3.common.SimpleBasePlayer] adapter, telemetry collector,
 * and eventual settings ViewModel — can operate against a single type.
 *
 * ### Threading
 * All control methods ([play], [pause], [stop], [seekTo], [load],
 * [enqueueNext], [release]) MUST be callable from any thread; engines
 * internally trampoline to their owning thread. State is observed through
 * lifecycle-safe [StateFlow]s.
 *
 * ### Optional telemetry
 * Only bit-perfect engines populate [currentFormat] and [pathReport]; other
 * engines emit `null` indefinitely. Consumers must tolerate null.
 */
interface AudioPlayerEngine {

    /** Identifies this strategy — used by [AudioEngineManager] when hot-swapping. */
    val engineType: EngineType

    /** Current playback lifecycle. */
    val state: StateFlow<EnginePlaybackState>

    /** Monotonically updated playhead position in milliseconds. */
    val positionMs: StateFlow<Long>

    /** Total duration of the loaded track in milliseconds; `0L` when unknown. */
    val durationMs: StateFlow<Long>

    /** URI of the track currently loaded into the engine, or `null` if idle. */
    val currentUri: StateFlow<String?>

    /** Decoded audio format of the currently playing track; `null` for non-bit-perfect engines. */
    val currentFormat: StateFlow<AudioFormatInfo?>

    /** Negotiated sink path report; `null` when not applicable to the active engine. */
    val pathReport: StateFlow<PipelinePathReport?>

    /** Last error message captured while transitioning into [EnginePlaybackState.ERROR]. */
    val lastErrorMessage: String?

    /**
     * Loads [uri] and optionally starts playback.
     *
     * @param uri            Content / file URI to load.
     * @param startPositionMs Seek target applied before playback begins.
     * @param autoPlay       When `true` the engine starts playing immediately.
     */
    fun load(uri: String, startPositionMs: Long = 0L, autoPlay: Boolean = false)

    /**
     * Best-effort preload of the follower track for gapless transitions.
     * Pass `null` to clear any preloaded item. Engines may silently downgrade
     * to a non-gapless transition when the next format is incompatible.
     */
    fun enqueueNext(uri: String?)

    fun play()
    fun pause()
    fun stop()
    fun seekTo(positionMs: Long)
    fun release()

    /**
     * Sets the output volume. [linearGain] is the raw UI position in [0.0, 1.0];
     * each engine applies its own taper internally (quadratic for the audiophile
     * path, passed straight to ExoPlayer for the standard path). Default no-op
     * so engines that manage volume through a separate mechanism (e.g. native
     * bridge) are not required to override.
     */
    fun setVolume(linearGain: Float) {}

    /**
     * Registers a push-event hook driving the Media3 adapter's
     * `invalidateState()` calls. At most one listener is supported; passing
     * `null` clears the current listener.
     */
    fun setListener(listener: Listener?)

    /**
     * Push-style callbacks emitted on engine state changes. Implementations
     * may invoke these from any thread — collectors are responsible for
     * trampolining to their required looper.
     */
    interface Listener {
        /** Fired whenever playback state, position, format, or error changes. */
        fun onEngineStateChanged()

        /** Fired when the engine auto-advances to the next queued track. */
        fun onTrackAdvanced()

        /**
         * Pull-style fallback invoked at end-of-stream when the engine's own
         * preload state is empty. Returns the URI of the track that should
         * follow the current one, or `null` when the queue genuinely has no
         * follower (end of queue with repeat off).
         *
         * This exists so a failed or lost [enqueueNext] preload can never
         * silently end playback while the playlist still has a next item —
         * the engine re-asks its listener before giving up. May be invoked
         * from the engine's audio thread; implementations must be thread-safe
         * and must not block.
         */
        fun onNextTrackUriRequested(): String? = null
    }
}

