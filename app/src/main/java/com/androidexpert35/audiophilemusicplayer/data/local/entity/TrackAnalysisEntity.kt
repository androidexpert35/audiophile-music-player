package com.androidexpert35.audiophilemusicplayer.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity caching the measured signal analysis of one piece of audio.
 *
 * Keyed by [TrackEntity.audioKey] rather than a track id, because that is the
 * only identifier that answers "is this the same audio?": a MediaStore delete
 * plus re-add mints a new id for byte-identical audio, and a file overwritten in
 * place keeps its id. Rows therefore outlive a re-index and are invalidated by an
 * actual change to the samples.
 *
 * Every measured column is nullable. The stationary ("Class S") and integral
 * ("Class I") measurements are produced by different passes at different times —
 * a short sampling decode versus a pass that must see the whole stream — so a
 * half-populated row is the normal state, not a defect.
 *
 * @property audioKey Content key of the analysed audio. Never blank: a blank key
 *   means the file could not be sampled at scan time and is not analysable.
 * @property schemaVersion Value of
 *   [com.androidexpert35.audiophilemusicplayer.domain.model.analysis.TrackAnalysis.SCHEMA_VERSION]
 *   in force when the row was last written. Reads filter on it, so bumping the
 *   constant retires every stored measurement without a Room migration.
 * @property analysedAtEpochSeconds Epoch seconds of the most recent pass to touch
 *   the row, whichever class it produced.
 * @property stationaryAnalysedAtEpochSeconds Epoch seconds of the Class S pass, or
 *   `null` when it has not run. This is the presence marker for the whole class:
 *   an individual feature can legitimately be `null` after a successful pass, so
 *   no single measured column can stand in for "was this measured?".
 * @property spectralRolloffHz Frequency below which effectively all energy lies.
 * @property spectralCentroidHz Spectral centre of mass in Hz.
 * @property spectralSlope Spectral tilt; negative falls away towards the treble.
 * @property noiseFloorDbfs Estimated noise floor in dBFS.
 * @property dcOffset Mean sample offset in `[-1, 1]`.
 * @property leftRmsDbfs RMS energy of the first channel in dBFS.
 * @property rightRmsDbfs RMS energy of the second channel in dBFS.
 * @property midRmsDbfs RMS energy of the `(L+R)/2` mid signal in dBFS.
 * @property sideRmsDbfs RMS energy of the `(L-R)/2` side signal in dBFS.
 * @property interChannelCorrelation Channel correlation in `[-1, 1]`.
 * @property windowCount Analysis windows behind the Class S averages.
 * @property frameCount PCM frames behind the Class S energy measures.
 * @property integralAnalysedAtEpochSeconds Epoch seconds of the Class I pass, or
 *   `null` when no complete pass has run. Presence marker for that class.
 * @property peakDbfs Absolute sample peak of the whole stream in dBFS.
 * @property integratedLufs EBU R128 integrated loudness in LUFS.
 * @property plr Peak-to-loudness ratio in dB.
 * @property clippingRatio Fraction of samples at or beyond full scale, in `[0, 1]`.
 */
@Entity(
    tableName = "track_analysis",
    indices = [Index(value = ["schemaVersion"])]
)
data class TrackAnalysisEntity(
    @PrimaryKey val audioKey: String,
    val schemaVersion: Int,
    val analysedAtEpochSeconds: Long,
    val stationaryAnalysedAtEpochSeconds: Long? = null,
    val spectralRolloffHz: Double? = null,
    val spectralCentroidHz: Double? = null,
    val spectralSlope: Double? = null,
    val noiseFloorDbfs: Double? = null,
    val dcOffset: Double? = null,
    val leftRmsDbfs: Double? = null,
    val rightRmsDbfs: Double? = null,
    val midRmsDbfs: Double? = null,
    val sideRmsDbfs: Double? = null,
    val interChannelCorrelation: Double? = null,
    val windowCount: Int? = null,
    val frameCount: Long? = null,
    val integralAnalysedAtEpochSeconds: Long? = null,
    val peakDbfs: Double? = null,
    val integratedLufs: Double? = null,
    val plr: Double? = null,
    val clippingRatio: Double? = null,
)
