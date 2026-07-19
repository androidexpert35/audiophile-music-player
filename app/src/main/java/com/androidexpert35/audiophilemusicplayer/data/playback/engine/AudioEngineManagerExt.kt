package com.androidexpert35.audiophilemusicplayer.data.playback.engine

/**
 * Suspending extension on [AudioEngineManager] that loads a track and waits
 * for the engine to reach [EnginePlaybackState.READY] before returning.
 *
 * ### Why this exists
 *
 * The two stock load primitives have a subtle contract difference:
 *
 * | Method | Thread | Atomicity |
 * |---|---|---|
 * | `engine.load(uri, pos, autoPlay = true)` → `loadAndPlay` on the audio thread | Single handler post | **Atomic** — decoder + play in one message; safe to call from any context |
 * | `engine.load(uri, pos, autoPlay = false)` → `loadTrack` on the audio thread | Handler post | **Non-atomic from coroutines** — a separately issued `engine.play()` from a *different* coroutine dispatcher may arrive before the `loadTrack` message is even consumed, producing a silent play-drop |
 *
 * This function uses [EngineReadinessGate.awaitReady] to bridge the gap: it
 * posts `loadTrack`, then suspends on the engine's [AudioEngineManager.state]
 * flow until [EnginePlaybackState.READY] (or [EnginePlaybackState.ERROR]) is
 * observed. Only then should the caller issue `engine.play()`, guaranteeing
 * the audio thread has already processed `doLoadTrack` and `currentDecoder`
 * is non-null.
 *
 * ### Usage
 *
 * ```kotlin
 * // Previously — unsafe when called from a coroutine on a different dispatcher:
 * engine.load(uri, savedPositionMs, autoPlay = false)
 * engine.play()   // ← race: may arrive before loadTrack executes
 *
 * // Safe alternative:
 * val settled = engine.loadAndAwaitReady(uri, savedPositionMs)
 * if (settled == EnginePlaybackState.READY) {
 *     engine.play()
 * }
 * ```
 *
 * When `autoPlay = true` is acceptable (no intermediate latency-sensitive
 * work between load and play), prefer the fully atomic:
 * ```kotlin
 * engine.load(uri, startPositionMs, autoPlay = true)
 * ```
 *
 * ### Timeout
 *
 * No built-in timeout — apply `withTimeout` at the call site when bounded
 * wait is required (e.g. 3 s for a cold-start restoration entry point).
 *
 * @param uri            Content URI of the track to load.
 * @param startPositionMs Seek offset in milliseconds; defaults to `0`.
 * @return The [EnginePlaybackState] at the time the gate opens — either
 *   [EnginePlaybackState.READY] (safe to play) or [EnginePlaybackState.ERROR]
 *   (load failed; do not issue play).
 */
suspend fun AudioEngineManager.loadAndAwaitReady(
    uri: String,
    startPositionMs: Long = 0L,
): EnginePlaybackState {
    load(uri, startPositionMs, autoPlay = false)
    return EngineReadinessGate.awaitReady(state)
}

