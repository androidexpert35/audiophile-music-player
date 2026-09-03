package com.androidexpert35.audiophilemusicplayer.data.mapper

import com.androidexpert35.audiophilemusicplayer.data.local.entity.TrackAnalysisEntity
import com.androidexpert35.audiophilemusicplayer.data.playback.analysis.AudioAnalysisFeatures
import com.androidexpert35.audiophilemusicplayer.data.playback.analysis.AudioIntegralFeatures
import com.androidexpert35.audiophilemusicplayer.domain.model.analysis.IntegralAnalysis
import com.androidexpert35.audiophilemusicplayer.domain.model.analysis.StationaryAnalysis
import com.androidexpert35.audiophilemusicplayer.domain.model.analysis.TrackAnalysis

/**
 * Maps cached signal measurements between Room rows and the framework-free domain model,
 * and decides how a newly measured class is folded into a row that already exists.
 */

/**
 * Converts a stored row into the domain model, honouring the schema version.
 *
 * @receiver Row as read from `track_analysis`.
 * @param currentSchemaVersion Version whose measurements are still meaningful.
 * @return The domain analysis, or `null` when the row was written under a superseded
 *   version — such a row is deliberately indistinguishable from "never analysed", so it
 *   gets recomputed instead of trusted.
 */
fun TrackAnalysisEntity.toDomain(currentSchemaVersion: Int): TrackAnalysis? {
    if (schemaVersion != currentSchemaVersion) return null
    return TrackAnalysis(
        audioKey = audioKey,
        schemaVersion = schemaVersion,
        analysedAtEpochSeconds = analysedAtEpochSeconds,
        stationary = toStationaryAnalysis(),
        integral = toIntegralAnalysis(),
    )
}

/**
 * Builds the row to store after a stationary pass, preserving what is still valid.
 *
 * Integral columns are carried over only when [existing] was written under
 * [schemaVersion]; a row from an older version is dropped whole, because its numbers no
 * longer mean what the new ones do and half a row of each would be worse than none.
 *
 * @param existing Row currently stored for this key, or `null` when there is none.
 * @param audioKey Content key of the measured audio.
 * @param stationary Measurements produced by the Class S pass.
 * @param schemaVersion Currently valid measurement schema version.
 * @param nowEpochSeconds Timestamp to stamp on the write.
 * @return The complete row to upsert.
 */
fun mergeStationaryAnalysis(
    existing: TrackAnalysisEntity?,
    audioKey: String,
    stationary: StationaryAnalysis,
    schemaVersion: Int,
    nowEpochSeconds: Long,
): TrackAnalysisEntity {
    val retained = existing?.takeIf { it.schemaVersion == schemaVersion }
    return TrackAnalysisEntity(
        audioKey = audioKey,
        schemaVersion = schemaVersion,
        analysedAtEpochSeconds = nowEpochSeconds,
        stationaryAnalysedAtEpochSeconds = nowEpochSeconds,
        spectralRolloffHz = stationary.spectralRolloffHz,
        spectralCentroidHz = stationary.spectralCentroidHz,
        spectralSlope = stationary.spectralSlope,
        noiseFloorDbfs = stationary.noiseFloorDbfs,
        dcOffset = stationary.dcOffset,
        leftRmsDbfs = stationary.leftRmsDbfs,
        rightRmsDbfs = stationary.rightRmsDbfs,
        midRmsDbfs = stationary.midRmsDbfs,
        sideRmsDbfs = stationary.sideRmsDbfs,
        interChannelCorrelation = stationary.interChannelCorrelation,
        windowCount = stationary.windowCount,
        frameCount = stationary.frameCount,
        integralAnalysedAtEpochSeconds = retained?.integralAnalysedAtEpochSeconds,
        peakDbfs = retained?.peakDbfs,
        integratedLufs = retained?.integratedLufs,
        plr = retained?.plr,
        clippingRatio = retained?.clippingRatio,
    )
}

/**
 * Builds the row to store after an integral pass, preserving what is still valid.
 *
 * The mirror of [mergeStationaryAnalysis]; the same schema-version rule applies.
 *
 * @param existing Row currently stored for this key, or `null` when there is none.
 * @param audioKey Content key of the measured audio.
 * @param integral Measurements produced by a pass that saw the whole stream.
 * @param schemaVersion Currently valid measurement schema version.
 * @param nowEpochSeconds Timestamp to stamp on the write.
 * @return The complete row to upsert.
 */
fun mergeIntegralAnalysis(
    existing: TrackAnalysisEntity?,
    audioKey: String,
    integral: IntegralAnalysis,
    schemaVersion: Int,
    nowEpochSeconds: Long,
): TrackAnalysisEntity {
    val retained = existing?.takeIf { it.schemaVersion == schemaVersion }
    return TrackAnalysisEntity(
        audioKey = audioKey,
        schemaVersion = schemaVersion,
        analysedAtEpochSeconds = nowEpochSeconds,
        stationaryAnalysedAtEpochSeconds = retained?.stationaryAnalysedAtEpochSeconds,
        spectralRolloffHz = retained?.spectralRolloffHz,
        spectralCentroidHz = retained?.spectralCentroidHz,
        spectralSlope = retained?.spectralSlope,
        noiseFloorDbfs = retained?.noiseFloorDbfs,
        dcOffset = retained?.dcOffset,
        leftRmsDbfs = retained?.leftRmsDbfs,
        rightRmsDbfs = retained?.rightRmsDbfs,
        midRmsDbfs = retained?.midRmsDbfs,
        sideRmsDbfs = retained?.sideRmsDbfs,
        interChannelCorrelation = retained?.interChannelCorrelation,
        windowCount = retained?.windowCount,
        frameCount = retained?.frameCount,
        integralAnalysedAtEpochSeconds = nowEpochSeconds,
        peakDbfs = integral.peakDbfs,
        integratedLufs = integral.integratedLufs,
        plr = integral.plr,
        clippingRatio = integral.clippingRatio,
    )
}

/**
 * Carries a fresh measurement out of the native bridge and into the domain.
 *
 * The two types hold the same numbers on purpose and are kept apart on purpose: one is
 * the wire shape of the native feature vector, the other is what the rest of the app is
 * allowed to see. This is the single seam between them, so a change to the vector layout
 * shows up here rather than everywhere a measurement is read.
 *
 * @receiver Aggregate produced by one sampling pass.
 * @return The same measurements as the framework-free domain model.
 */
fun AudioAnalysisFeatures.toStationaryAnalysis(): StationaryAnalysis = StationaryAnalysis(
    spectralRolloffHz = spectralRolloffHz,
    spectralCentroidHz = spectralCentroidHz,
    spectralSlope = spectralSlope,
    noiseFloorDbfs = noiseFloorDbfs,
    dcOffset = dcOffset,
    leftRmsDbfs = leftRmsDbfs,
    rightRmsDbfs = rightRmsDbfs,
    midRmsDbfs = midRmsDbfs,
    sideRmsDbfs = sideRmsDbfs,
    interChannelCorrelation = interChannelCorrelation,
    windowCount = windowCount,
    frameCount = frameCount,
)

/**
 * Carries a fresh full-file measurement out of the native bridge and into the domain.
 *
 * The counterpart of [AudioAnalysisFeatures.toStationaryAnalysis], and the single seam
 * between the integral feature vector and what the rest of the app is allowed to see.
 *
 * The native vector is wider than the domain model: it also carries the true peak and the
 * flat-top run-length statistics, which the cache has no columns for. They are dropped
 * here rather than silently folded into a column that means something else — the pass
 * still reports them to its caller and its log, and giving them a home is a schema
 * change, not a mapping decision.
 *
 * @receiver Aggregate produced by one full-file pass.
 * @return The measurements the analysis cache can hold, as the framework-free domain
 *   model.
 */
fun AudioIntegralFeatures.toIntegralAnalysis(): IntegralAnalysis = IntegralAnalysis(
    peakDbfs = samplePeakDbfs,
    integratedLufs = integratedLufs,
    plr = plrDb,
    clippingRatio = clippingRatio,
)

/**
 * Reconstructs the Class S half of a row.
 *
 * @receiver Row whose stationary columns are being read.
 * @return The stationary measurements, or `null` when that pass never ran. The
 *   per-class timestamp is the marker rather than any measured column, because a
 *   successful pass may legitimately leave individual features unmeasured.
 */
private fun TrackAnalysisEntity.toStationaryAnalysis(): StationaryAnalysis? {
    if (stationaryAnalysedAtEpochSeconds == null) return null
    return StationaryAnalysis(
        spectralRolloffHz = spectralRolloffHz,
        spectralCentroidHz = spectralCentroidHz,
        spectralSlope = spectralSlope,
        noiseFloorDbfs = noiseFloorDbfs,
        dcOffset = dcOffset,
        leftRmsDbfs = leftRmsDbfs,
        rightRmsDbfs = rightRmsDbfs,
        midRmsDbfs = midRmsDbfs,
        sideRmsDbfs = sideRmsDbfs,
        interChannelCorrelation = interChannelCorrelation,
        windowCount = windowCount ?: 0,
        frameCount = frameCount ?: 0L,
    )
}

/**
 * Reconstructs the Class I half of a row.
 *
 * @receiver Row whose integral columns are being read.
 * @return The integral measurements, or `null` when no complete pass has run.
 */
private fun TrackAnalysisEntity.toIntegralAnalysis(): IntegralAnalysis? {
    if (integralAnalysedAtEpochSeconds == null) return null
    return IntegralAnalysis(
        peakDbfs = peakDbfs,
        integratedLufs = integratedLufs,
        plr = plr,
        clippingRatio = clippingRatio,
    )
}
