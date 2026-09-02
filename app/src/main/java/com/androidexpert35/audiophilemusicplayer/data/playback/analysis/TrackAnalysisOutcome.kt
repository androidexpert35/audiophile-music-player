package com.androidexpert35.audiophilemusicplayer.data.playback.analysis

import com.androidexpert35.audiophilemusicplayer.domain.model.analysis.StationaryAnalysis

/**
 * What one call to [TrackSignalAnalyser.analyseIfNeeded] actually did.
 *
 * A skip is a normal, expected result and not a failure: most of the library is either
 * already measured or is something a measurement would say nothing useful about. Callers
 * that batch over the library need to tell those apart from a real error — a skipped
 * track should never be retried in the same pass, a failed one may be — which is why the
 * reason is carried rather than folded into a boolean.
 */
sealed interface TrackAnalysisOutcome {

    /**
     * A measurement ran and its result was written to the analysis cache.
     *
     * @property stationary The Class S values that were persisted.
     */
    data class Analysed(val stationary: StationaryAnalysis) : TrackAnalysisOutcome

    /**
     * No measurement ran, and nothing was written.
     *
     * @property reason Why the track was passed over.
     */
    data class Skipped(val reason: SkipReason) : TrackAnalysisOutcome

    /** Why a track was passed over without being measured. */
    enum class SkipReason {

        /** The track carries no content key, so a result could not be addressed to it. */
        MISSING_AUDIO_KEY,

        /**
         * The source is DSD, which bypasses the DSP stage entirely and therefore has
         * nothing to gain from a measurement.
         */
        DSD_SOURCE,

        /** Too short to place sample windows away from the fades at either end. */
        TOO_SHORT,

        /** The cache already holds Class S values written at the current schema version. */
        ALREADY_ANALYSED,

        /**
         * The measurement graph could not be built — FFmpeg is absent from this build, or
         * it refused the decoded PCM shape. Not an error: there is simply nothing to
         * measure with, and the caller records the track as not analysable.
         */
        MEASUREMENT_UNAVAILABLE,
    }
}
