package com.androidexpert35.audiophilemusicplayer.data.playback.engine

import android.util.Log
import com.androidexpert35.audiophilemusicplayer.data.playback.engine.EngineReadinessGate.awaitReady
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * Suspending gate that delays playback commands until the engine reaches
 * [EnginePlaybackState.READY] or [EnginePlaybackState.PLAYING].
 *
 * ### Problem this solves
 *
 * `BitPerfectPlaybackEngine.play()` posts work to a `Handler` on the audio
 * thread. If `play()` is posted before `doLoadTrack` has returned on that same
 * thread, `currentDecoder` will be `null` when `play` executes and the command
 * is silently dropped (Case A — cold-start no auto-play).
 *
 * **Because `Handler` is a serial queue, this race is NOT possible when `play()`
 * is always posted from a single site after `loadTrack` — both are on the same
 * handler.** The race *does* occur when `play()` originates from a different
 * execution context (e.g. a session restorer on a coroutine scope, a Bluetooth
 * auto-connect receiver, or a test harness) before the `loadTrack` message even
 * reaches the handler.
 *
 * ### Usage
 *
 * ```kotlin
 * // Instead of:
 * engine.loadTrack(uri, positionMs)
 * engine.play()   // ← may arrive before loadTrack executes
 *
 * // Use:
 * engine.loadTrack(uri, positionMs)
 * EngineReadinessGate.awaitReady(engine.state)
 * engine.play()   // ← guaranteed currentDecoder != null
 * ```
 *
 * When the caller already holds a coroutine scope this is a zero-overhead
 * one-liners. When no coroutine is available, prefer `loadAndPlay` which is a
 * single atomic handler post and never has this race.
 *
 * ### Timeout behaviour
 *
 * [awaitReady] has no built-in timeout. Apply `withTimeout` at the call site
 * when bounded wait is required (e.g. ~3 s for a cold-start restore).
 */
object EngineReadinessGate {

    private const val TAG = "EngineReadinessGate"

    /**
     * Suspends until [stateFlow] emits [EnginePlaybackState.READY] or
     * [EnginePlaybackState.PLAYING], then returns.
     *
     * Returns immediately when the current state is already one of the
     * two ready states, making this safe to call unconditionally without
     * double-checking.
     *
     * Returns without suspending when the state is [EnginePlaybackState.ERROR]
     * so callers are not stuck waiting forever after a load failure. The
     * caller should check the state and surface the error rather than
     * issuing a `play()` after an error gate return.
     *
     * @param stateFlow The engine's publicly exposed [StateFlow] of [EnginePlaybackState].
     * @return The settled [EnginePlaybackState] at the time the gate opens.
     */
    suspend fun awaitReady(stateFlow: StateFlow<EnginePlaybackState>): EnginePlaybackState {
        val settled = stateFlow.first { state ->
            when (state) {
                EnginePlaybackState.READY,
                EnginePlaybackState.PLAYING,
                EnginePlaybackState.ERROR,
                EnginePlaybackState.ENDED -> true
                else -> false
            }
        }
        if (settled == EnginePlaybackState.ERROR) {
            Log.w(TAG, "awaitReady: gate opened on ERROR state — caller should NOT issue play()")
        }
        return settled
    }
}

