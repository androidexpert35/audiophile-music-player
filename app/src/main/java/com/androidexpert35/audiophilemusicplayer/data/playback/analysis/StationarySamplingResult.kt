package com.androidexpert35.audiophilemusicplayer.data.playback.analysis

/**
 * What one pass of a [StationarySampler] over a source file produced.
 *
 * The sampler owns the only two components that cannot exist outside a device — a native
 * decoder session and a native filter graph — so it reports every way a pass can end as a
 * value instead of an exception. That keeps the orchestrator's policy (what to skip, what
 * to persist, what to report as failed) testable on the JVM, and it is why
 * [Failed] carries the cause rather than throwing it.
 */
sealed interface StationarySamplingResult {

    /**
     * The graph measured the sampled windows.
     *
     * @property features Aggregate over every window that reached the stats filters. May
     *   still be [AudioAnalysisFeatures.isEmpty] when no window did.
     */
    data class Measured(val features: AudioAnalysisFeatures) : StationarySamplingResult

    /**
     * The opened stream turned out to be DSD.
     *
     * Reported by the sampler rather than assumed by the caller, because the scan-time
     * MIME type is not always right about a `.dsf`/`.dff` file and the decoder is.
     */
    data object DsdSource : StationarySamplingResult

    /**
     * No measurement graph could be built for this stream.
     *
     * Either FFmpeg is not provisioned in this build, or the decoded PCM shape was
     * refused by the analysis bridge. Distinct from [Failed]: nothing went wrong, there
     * is simply nothing to measure with.
     */
    data object Unavailable : StationarySamplingResult

    /**
     * The pass broke down — the source would not open, or decoding threw.
     *
     * @property cause The failure, kept so the caller can report it without a second
     *   guess at what happened.
     */
    data class Failed(val cause: Throwable) : StationarySamplingResult
}
