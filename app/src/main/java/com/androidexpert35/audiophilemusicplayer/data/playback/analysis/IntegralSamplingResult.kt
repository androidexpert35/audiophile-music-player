package com.androidexpert35.audiophilemusicplayer.data.playback.analysis

/**
 * What one full-file pass of an [IntegralSampler] over a source produced.
 *
 * The sampler owns the only two components that cannot exist outside a device — a native
 * decoder session and a native filter graph — so it reports every way a pass can end as a
 * value instead of an exception. That keeps the orchestrator's policy (what to skip, what
 * to persist, what to report as failed) testable on the JVM, and it is why [Failed]
 * carries the cause rather than throwing it.
 *
 * Mirrors [StationarySamplingResult], with one variant it does not have: eligibility
 * depends on the *decoded* format, so a source can only be ruled out after it is open.
 */
sealed interface IntegralSamplingResult {

    /**
     * The graph measured the complete stream.
     *
     * @property features Aggregate over every sample that reached the graph. May still be
     *   [AudioIntegralFeatures.isEmpty] when no audio did.
     * @property elapsedMillis Wall-clock duration of the whole pass — open, decode, feed,
     *   read, close. This is the number that decides whether sweeping a library is
     *   viable, so it is measured here rather than estimated anywhere else.
     * @property decodedFrames PCM frames handed to the graph, so a cost can be expressed
     *   per second of audio as well as per track.
     */
    data class Measured(
        val features: AudioIntegralFeatures,
        val elapsedMillis: Long,
        val decodedFrames: Long,
    ) : IntegralSamplingResult

    /**
     * The opened stream is not audio an integral measurement would ever be read for.
     *
     * Decided by [isEligibleForIntegralAnalysis] against the decoded format, because that
     * is the only place bit depth and true codec are known. DSD lands here too: the
     * decoder is the authority on a `.dsf`/`.dff` file, not the scan-time MIME type.
     */
    data object Ineligible : IntegralSamplingResult

    /**
     * No measurement graph could be built for this stream.
     *
     * Either FFmpeg is not provisioned in this build, or the decoded PCM shape was
     * refused by the analysis bridge. Distinct from [Failed]: nothing went wrong, there is
     * simply nothing to measure with.
     */
    data object Unavailable : IntegralSamplingResult

    /**
     * The pass broke down — the source would not open, or decoding threw.
     *
     * @property cause The failure, kept so the caller can report it without a second
     *   guess at what happened.
     */
    data class Failed(val cause: Throwable) : IntegralSamplingResult
}
