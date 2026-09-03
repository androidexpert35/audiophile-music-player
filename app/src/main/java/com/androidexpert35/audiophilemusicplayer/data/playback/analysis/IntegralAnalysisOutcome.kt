package com.androidexpert35.audiophilemusicplayer.data.playback.analysis

import com.androidexpert35.audiophilemusicplayer.domain.model.analysis.IntegralAnalysis

/**
 * What one call to [TrackIntegralAnalyser.analyseIfNeeded] actually did.
 *
 * A skip is a normal, expected result and not a failure: a full-file pass is the most
 * expensive background work in the app, and most of a library is either already measured
 * or is audio no decision would read the result for. Callers that batch over the library
 * need to tell those apart from a real error — a skipped track should never be retried in
 * the same pass, a failed one may be — which is why the reason is carried rather than
 * folded into a boolean.
 */
sealed interface IntegralAnalysisOutcome {

    /**
     * A full-file measurement ran and its result was written to the analysis cache.
     *
     * @property integral The Class I values that were persisted.
     * @property elapsedMillis Wall-clock cost of the pass, carried out to whatever
     *   schedules the next one. Sweeping a library is only viable if this number is what
     *   the design assumed, so it is reported rather than discarded.
     */
    data class Analysed(
        val integral: IntegralAnalysis,
        val elapsedMillis: Long,
    ) : IntegralAnalysisOutcome

    /**
     * No measurement ran, and nothing was written.
     *
     * @property reason Why the track was passed over.
     */
    data class Skipped(val reason: SkipReason) : IntegralAnalysisOutcome

    /** Why a track was passed over without being measured. */
    enum class SkipReason {

        /** The track carries no content key, so a result could not be addressed to it. */
        MISSING_AUDIO_KEY,

        /**
         * The source is DSD, which bypasses the DSP stage entirely and therefore has
         * nothing to gain from a measurement. The cheap check, from scan metadata; the
         * decoder confirms it again and reports it as [NOT_ELIGIBLE].
         */
        DSD_SOURCE,

        /**
         * The decoded source is not audio an integral measurement would ever be read for
         * — a lossy source, or one already at native hi-res. Mirrors the Hi-Res Remaster
         * gate; see [isEligibleForIntegralAnalysis].
         */
        NOT_ELIGIBLE,

        /** The cache already holds Class I values written at the current schema version. */
        ALREADY_ANALYSED,

        /**
         * The measurement graph could not be built — FFmpeg is absent from this build, or
         * it refused the decoded PCM shape. Not an error: there is simply nothing to
         * measure with, and the caller records the track as not analysable.
         */
        MEASUREMENT_UNAVAILABLE,
    }
}
