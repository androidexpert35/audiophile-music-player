package com.androidexpert35.audiophilemusicplayer.domain.model.analysis

import com.androidexpert35.audiophilemusicplayer.domain.model.analysis.TrackAnalysis.Companion.SCHEMA_VERSION


/**
 * Cached signal measurements for one piece of audio, keyed by its content.
 *
 * The key is the track's `audioKey` — the content key derived by the scanners,
 * not a MediaStore row id — so an analysis survives a re-index and a delete plus
 * re-add of the same file, and is discarded when the audio itself is replaced.
 * An empty key means "not analysable" and never identifies a row.
 *
 * A cached analysis is half-populated by design: [stationary] comes from a
 * short sampling pass while [integral] can only come from a pass that saw the
 * whole stream, and the two run at different times. Either may be `null`.
 *
 * @property audioKey Content key of the audio these measurements describe.
 * @property schemaVersion Value of [SCHEMA_VERSION] in force when the row was
 *   written. A row carrying any other value is stale and read as absent.
 * @property analysedAtEpochSeconds Epoch seconds of the most recent pass that
 *   wrote to this row, whichever class it produced.
 * @property stationary Class S measurements, or `null` when that pass has not
 *   run for this audio at the current [schemaVersion].
 * @property integral Class I measurements, or `null` when no complete pass has
 *   run for this audio at the current [schemaVersion].
 */
data class TrackAnalysis(
    val audioKey: String,
    val schemaVersion: Int,
    val analysedAtEpochSeconds: Long,
    val stationary: StationaryAnalysis?,
    val integral: IntegralAnalysis?,
) {
    companion object {
        /**
         * Meaning-version of every measured column in the analysis cache.
         *
         * Bump this whenever the *interpretation* of any measured value changes
         * — a different filter graph, a different unit, a corrected aggregation
         * — and every existing row silently reads as absent and is recomputed.
         * That is deliberately not a Room migration: the schema is unchanged,
         * only the numbers in it stopped meaning what they used to.
         *
         * Do **not** bump it when adding a column; that is a real migration.
         */
        const val SCHEMA_VERSION: Int = 1
    }
}
