package com.androidexpert35.audiophilemusicplayer.data.mapper

import com.androidexpert35.audiophilemusicplayer.domain.model.analysis.IntegralAnalysis
import com.androidexpert35.audiophilemusicplayer.domain.model.analysis.StationaryAnalysis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the merge and mapping rules of the measured-analysis cache.
 *
 * Two properties are load-bearing and are what these cases pin down: the two
 * measurement passes write at different times and must not clobber each other,
 * and a row written under a superseded schema version must read as if it were
 * never there rather than be silently trusted or half-merged.
 */
class TrackAnalysisMapperTest {

    @Test
    fun `given cached integral values when stationary is merged then integral survives`() {
        val existing = integralRow(nowEpochSeconds = 1_000L)

        val merged = mergeStationaryAnalysis(
            existing = existing,
            audioKey = AUDIO_KEY,
            stationary = stationary(),
            schemaVersion = SCHEMA_VERSION,
            nowEpochSeconds = 2_000L,
        )

        val analysis = merged.toDomain(SCHEMA_VERSION)
        assertEquals(integral(), analysis?.integral)
        assertEquals(stationary(), analysis?.stationary)
        assertEquals(2_000L, merged.analysedAtEpochSeconds)
        assertEquals(1_000L, merged.integralAnalysedAtEpochSeconds)
        assertEquals(2_000L, merged.stationaryAnalysedAtEpochSeconds)
    }

    @Test
    fun `given cached stationary values when integral is merged then stationary survives`() {
        val existing = stationaryRow(nowEpochSeconds = 1_000L)

        val merged = mergeIntegralAnalysis(
            existing = existing,
            audioKey = AUDIO_KEY,
            integral = integral(),
            schemaVersion = SCHEMA_VERSION,
            nowEpochSeconds = 2_000L,
        )

        val analysis = merged.toDomain(SCHEMA_VERSION)
        assertEquals(stationary(), analysis?.stationary)
        assertEquals(integral(), analysis?.integral)
        assertEquals(1_000L, merged.stationaryAnalysedAtEpochSeconds)
        assertEquals(2_000L, merged.integralAnalysedAtEpochSeconds)
    }

    @Test
    fun `given no previous row when stationary is merged then integral columns stay null`() {
        val merged = stationaryRow(nowEpochSeconds = 1_000L)

        assertNull(merged.integralAnalysedAtEpochSeconds)
        assertNull(merged.peakDbfs)
        assertNull(merged.integratedLufs)
        assertNull(merged.plr)
        assertNull(merged.clippingRatio)
        assertNull(merged.toDomain(SCHEMA_VERSION)?.integral)
    }

    @Test
    fun `given no previous row when integral is merged then stationary columns stay null`() {
        val merged = integralRow(nowEpochSeconds = 1_000L)

        assertNull(merged.stationaryAnalysedAtEpochSeconds)
        assertNull(merged.spectralRolloffHz)
        assertNull(merged.interChannelCorrelation)
        assertNull(merged.windowCount)
        assertNull(merged.frameCount)
        assertNull(merged.toDomain(SCHEMA_VERSION)?.stationary)
    }

    @Test
    fun `given a row from an older schema when stationary is merged then its values are dropped`() {
        val stale = mergeIntegralAnalysis(
            existing = null,
            audioKey = AUDIO_KEY,
            integral = integral(),
            schemaVersion = SCHEMA_VERSION - 1,
            nowEpochSeconds = 1_000L,
        )

        val merged = mergeStationaryAnalysis(
            existing = stale,
            audioKey = AUDIO_KEY,
            stationary = stationary(),
            schemaVersion = SCHEMA_VERSION,
            nowEpochSeconds = 2_000L,
        )

        assertEquals(SCHEMA_VERSION, merged.schemaVersion)
        assertNull(merged.peakDbfs)
        assertNull(merged.integralAnalysedAtEpochSeconds)
    }

    @Test
    fun `given a row at the current schema when mapped then both classes are exposed`() {
        val row = mergeIntegralAnalysis(
            existing = stationaryRow(nowEpochSeconds = 1_000L),
            audioKey = AUDIO_KEY,
            integral = integral(),
            schemaVersion = SCHEMA_VERSION,
            nowEpochSeconds = 2_000L,
        )

        val analysis = row.toDomain(SCHEMA_VERSION)

        assertNotNull(analysis)
        assertEquals(AUDIO_KEY, analysis?.audioKey)
        assertEquals(SCHEMA_VERSION, analysis?.schemaVersion)
        assertEquals(2_000L, analysis?.analysedAtEpochSeconds)
        assertEquals(stationary(), analysis?.stationary)
        assertEquals(integral(), analysis?.integral)
    }

    @Test
    fun `given a superseded schema version when mapped then the row reads as absent`() {
        val row = stationaryRow(nowEpochSeconds = 1_000L)

        assertNull(row.toDomain(SCHEMA_VERSION + 1))
    }

    private fun stationaryRow(nowEpochSeconds: Long) = mergeStationaryAnalysis(
        existing = null,
        audioKey = AUDIO_KEY,
        stationary = stationary(),
        schemaVersion = SCHEMA_VERSION,
        nowEpochSeconds = nowEpochSeconds,
    )

    private fun integralRow(nowEpochSeconds: Long) = mergeIntegralAnalysis(
        existing = null,
        audioKey = AUDIO_KEY,
        integral = integral(),
        schemaVersion = SCHEMA_VERSION,
        nowEpochSeconds = nowEpochSeconds,
    )

    private fun stationary(): StationaryAnalysis = StationaryAnalysis(
        spectralRolloffHz = 19_500.0,
        spectralCentroidHz = 2_400.0,
        spectralSlope = -0.7,
        noiseFloorDbfs = -96.0,
        dcOffset = 0.0001,
        leftRmsDbfs = -14.0,
        rightRmsDbfs = -14.2,
        midRmsDbfs = -13.8,
        sideRmsDbfs = -22.0,
        interChannelCorrelation = 0.93,
        windowCount = 4,
        frameCount = 176_400L,
    )

    private fun integral(): IntegralAnalysis = IntegralAnalysis(
        peakDbfs = -1.2,
        integratedLufs = -9.5,
        plr = 8.3,
        clippingRatio = 0.004,
    )

    private companion object {
        const val AUDIO_KEY = "1:0a3f:9c8b7a6d5e4f3a2b1c0d9e8f7a6b5c4d"
        const val SCHEMA_VERSION = 7
    }
}
