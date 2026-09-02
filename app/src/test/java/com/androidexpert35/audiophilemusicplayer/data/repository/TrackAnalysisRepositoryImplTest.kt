package com.androidexpert35.audiophilemusicplayer.data.repository

import com.androidexpert35.audiophilemusicplayer.data.local.dao.TrackAnalysisDao
import com.androidexpert35.audiophilemusicplayer.data.local.entity.TrackAnalysisEntity
import com.androidexpert35.audiophilemusicplayer.domain.model.analysis.IntegralAnalysis
import com.androidexpert35.audiophilemusicplayer.domain.model.analysis.StationaryAnalysis
import com.androidexpert35.audiophilemusicplayer.domain.model.analysis.TrackAnalysis
import com.tony.coreui.domain.resource.Resource
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioural tests for [TrackAnalysisRepositoryImpl].
 *
 * The cache is written by two passes that never run together, so the cases that
 * matter are the ones where one class of measurement could quietly erase the
 * other, and the one where a change in the meaning of the numbers has to retire
 * every stored row without a Room migration.
 *
 * The DAO is a real in-memory map rather than a mock: the repository's whole job
 * here is the read-modify-write cycle around it, and a mock that always answered
 * `null` would make these assertions pass for the wrong reason.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrackAnalysisRepositoryImplTest {

    private val dao = FakeTrackAnalysisDao()

    @Test
    fun `given a stationary pass when integral is stored then stationary values are kept`() =
        runTest {
            val repository = repository()
            repository.saveStationaryAnalysis(AUDIO_KEY, stationary())

            repository.saveIntegralAnalysis(AUDIO_KEY, integral())

            val analysis = (repository.getAnalysis(AUDIO_KEY) as Resource.Success).data
            assertEquals(stationary(), analysis?.stationary)
            assertEquals(integral(), analysis?.integral)
        }

    @Test
    fun `given an integral pass when stationary is stored then integral values are kept`() =
        runTest {
            val repository = repository()
            repository.saveIntegralAnalysis(AUDIO_KEY, integral())

            repository.saveStationaryAnalysis(AUDIO_KEY, stationary())

            val analysis = (repository.getAnalysis(AUDIO_KEY) as Resource.Success).data
            assertEquals(integral(), analysis?.integral)
            assertEquals(stationary(), analysis?.stationary)
        }

    @Test
    fun `given only a stationary pass when read then the integral half is absent`() = runTest {
        val repository = repository()

        repository.saveStationaryAnalysis(AUDIO_KEY, stationary())

        val analysis = (repository.getAnalysis(AUDIO_KEY) as Resource.Success).data
        assertNotNull(analysis?.stationary)
        assertNull(analysis?.integral)
    }

    @Test
    fun `given a row written under an older schema when read then it is absent`() = runTest {
        dao.rows[AUDIO_KEY] = TrackAnalysisEntity(
            audioKey = AUDIO_KEY,
            schemaVersion = TrackAnalysis.SCHEMA_VERSION - 1,
            analysedAtEpochSeconds = 1_000L,
            stationaryAnalysedAtEpochSeconds = 1_000L,
            spectralRolloffHz = 15_000.0,
        )

        val analysis = (repository().getAnalysis(AUDIO_KEY) as Resource.Success).data

        assertNull(analysis)
    }

    @Test
    fun `given a row written under an older schema when rewritten then stale values are gone`() =
        runTest {
            dao.rows[AUDIO_KEY] = TrackAnalysisEntity(
                audioKey = AUDIO_KEY,
                schemaVersion = TrackAnalysis.SCHEMA_VERSION - 1,
                analysedAtEpochSeconds = 1_000L,
                integralAnalysedAtEpochSeconds = 1_000L,
                peakDbfs = -0.1,
            )

            val repository = repository()
            repository.saveStationaryAnalysis(AUDIO_KEY, stationary())

            val analysis = (repository.getAnalysis(AUDIO_KEY) as Resource.Success).data
            assertEquals(stationary(), analysis?.stationary)
            assertNull(analysis?.integral)
        }

    @Test
    fun `given a blank audio key when reading then nothing is looked up`() = runTest {
        val result = repository().getAnalysis("")

        assertTrue(result is Resource.Success)
        assertNull((result as Resource.Success).data)
        assertEquals(0, dao.readCount)
    }

    @Test
    fun `given a blank audio key when storing then the write is rejected`() = runTest {
        val result = repository().saveStationaryAnalysis("", stationary())

        assertTrue(result is Resource.Error)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `given a failing dao when storing then the failure becomes an error result`() = runTest {
        val failingDao = mockk<TrackAnalysisDao>()
        coEvery { failingDao.getByAudioKey(any()) } throws IllegalStateException("disk full")

        val result = repository(failingDao).saveStationaryAnalysis(AUDIO_KEY, stationary())

        assertTrue(result is Resource.Error)
    }

    @Test
    fun `given rows missing a class when counted then only complete rows are excluded`() =
        runTest {
            val repository = repository()
            repository.saveStationaryAnalysis(AUDIO_KEY, stationary())
            repository.saveIntegralAnalysis(OTHER_AUDIO_KEY, integral())

            val missingStationary =
                (repository.countMissingStationaryAnalysis() as Resource.Success).data
            val missingIntegral =
                (repository.countMissingIntegralAnalysis() as Resource.Success).data

            assertEquals(1, missingStationary)
            assertEquals(1, missingIntegral)
        }

    @Test
    fun `given a stale row when counted then it counts as missing both classes`() = runTest {
        dao.rows[AUDIO_KEY] = TrackAnalysisEntity(
            audioKey = AUDIO_KEY,
            schemaVersion = TrackAnalysis.SCHEMA_VERSION - 1,
            analysedAtEpochSeconds = 1_000L,
            stationaryAnalysedAtEpochSeconds = 1_000L,
            integralAnalysedAtEpochSeconds = 1_000L,
        )
        val repository = repository()

        assertEquals(1, (repository.countMissingStationaryAnalysis() as Resource.Success).data)
        assertEquals(1, (repository.countMissingIntegralAnalysis() as Resource.Success).data)
    }

    private fun repository(dao: TrackAnalysisDao = this.dao) = TrackAnalysisRepositoryImpl(
        trackAnalysisDao = dao,
        ioDispatcher = UnconfinedTestDispatcher()
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

    /**
     * In-memory stand-in for the Room DAO, reproducing its upsert and counting
     * semantics so the repository's merge behaviour is what the tests observe.
     */
    private class FakeTrackAnalysisDao : TrackAnalysisDao {

        val rows: MutableMap<String, TrackAnalysisEntity> = mutableMapOf()
        var readCount: Int = 0
            private set

        override suspend fun getByAudioKey(audioKey: String): TrackAnalysisEntity? {
            readCount++
            return rows[audioKey]
        }

        override suspend fun upsert(entity: TrackAnalysisEntity) {
            rows[entity.audioKey] = entity
        }

        override suspend fun countMissingStationary(schemaVersion: Int): Int =
            rows.values.count {
                it.stationaryAnalysedAtEpochSeconds == null || it.schemaVersion != schemaVersion
            }

        override suspend fun countMissingIntegral(schemaVersion: Int): Int =
            rows.values.count {
                it.integralAnalysedAtEpochSeconds == null || it.schemaVersion != schemaVersion
            }
    }

    private companion object {
        const val AUDIO_KEY = "1:0a3f:9c8b7a6d5e4f3a2b1c0d9e8f7a6b5c4d"
        const val OTHER_AUDIO_KEY = "1:1b4e:0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a"
    }
}
