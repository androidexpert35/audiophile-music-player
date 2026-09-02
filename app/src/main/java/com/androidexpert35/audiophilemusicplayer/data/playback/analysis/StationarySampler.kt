package com.androidexpert35.audiophilemusicplayer.data.playback.analysis

/**
 * Measures the stationary signal properties of a source file, start to finish.
 *
 * One call owns a complete measurement pass: it opens its own decoder session, seeks to a
 * handful of windows spread across the stream, feeds them to a measurement graph and
 * releases both. Nothing survives the call, so two passes never share native state.
 *
 * ### Threading
 *
 * Implementations block and are **not** thread-safe: the native decoder and the
 * measurement bridge each require every call on a handle to come from one thread, so a
 * pass must run start to finish on the caller's thread. That thread must be a background
 * one — `@IoDispatcher` — and must never be `BitPerfectPlaybackEngine`'s
 * `THREAD_PRIORITY_AUDIO` HandlerThread.
 */
interface StationarySampler {

    /**
     * Runs one measurement pass over [sourcePath].
     *
     * @param sourcePath Path FFmpeg can open — a real filesystem path, or the
     *   `/proc/self/fd/<n>` trampoline produced for a `content://` URI. When it is a
     *   trampoline, the descriptor is consumed by this call and must not be reused.
     * @return The measured aggregate, or the reason no aggregate exists. Never throws for
     *   a source-level failure; those come back as
     *   [StationarySamplingResult.Failed].
     */
    fun sample(sourcePath: String): StationarySamplingResult
}
