package com.androidexpert35.audiophilemusicplayer.data.playback.analysis

/**
 * Measures the integral loudness properties of a source file over its whole length.
 *
 * One call owns a complete pass: it opens its own decoder session, decodes the stream
 * from beginning to end, feeds every frame to a measurement graph and releases both.
 * Nothing survives the call, so two passes never share native state.
 *
 * This exists because the cheap path cannot cover everything. Where the Kotlin write loop
 * already sees every sample, the same figures come free during playback; on the pure
 * bit-perfect libusb transport the native pump owns the data and Kotlin sees none of it,
 * and a track the user has never played has no listen to piggyback on. Those are the
 * cases this pass is for, and it is the expensive one — it decodes the entire file.
 *
 * ### Threading
 *
 * Implementations block for as long as the decode takes and are **not** thread-safe: the
 * native decoder and the measurement bridge each require every call on a handle to come
 * from one thread, so a pass must run start to finish on the caller's thread. That thread
 * must be a background one — `@IoDispatcher` — and must never be
 * `BitPerfectPlaybackEngine`'s `THREAD_PRIORITY_AUDIO` HandlerThread.
 */
interface IntegralSampler {

    /**
     * Runs one full-file measurement pass over [sourcePath].
     *
     * @param sourcePath Path FFmpeg can open — a real filesystem path, or the
     *   `/proc/self/fd/<n>` trampoline produced for a `content://` URI. When it is a
     *   trampoline, the descriptor is consumed by this call and must not be reused.
     * @return The measured aggregate with the cost of producing it, or the reason no
     *   aggregate exists. Never throws for a source-level failure; those come back as
     *   [IntegralSamplingResult.Failed].
     */
    fun measure(sourcePath: String): IntegralSamplingResult
}
