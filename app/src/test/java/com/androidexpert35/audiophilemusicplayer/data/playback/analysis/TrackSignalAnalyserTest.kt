package com.androidexpert35.audiophilemusicplayer.data.playback.analysis

import android.content.Context
import android.net.Uri
import android.util.Log
import com.androidexpert35.audiophilemusicplayer.domain.model.analysis.IntegralAnalysis
import com.androidexpert35.audiophilemusicplayer.domain.model.analysis.StationaryAnalysis
import com.androidexpert35.audiophilemusicplayer.domain.model.analysis.TrackAnalysis
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioCodec
import com.androidexpert35.audiophilemusicplayer.domain.repository.TrackAnalysisRepository
import com.tony.coreui.domain.resource.Resource
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Behavioural tests for [TrackSignalAnalyser].
 *
 * What matters about this component is what it refuses to do: it must not measure audio
 * that no decision will ever read, must not measure the same track twice, and must not
 * let a broken file reach the caller as an exception. Those are the cases here.
 *
 * The measurement pass itself is a fake. The real one owns an FFmpeg session and a native
 * filter graph and cannot exist on the JVM — which is exactly why the orchestrator talks
 * to it through [StationarySampler] rather than building one itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrackSignalAnalyserTest {

    private val repository = FakeTrackAnalysisRepository()
    private val context = mockk<Context>()

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0

        // `resolveUriToPath` parses the URI before anything else; android.net.Uri is a
        // stub in unit tests, so the parse itself has to be answered here.
        mockkStatic(Uri::class)
        val parsed = mockk<Uri>()
        every { Uri.parse(any()) } returns parsed
        every { parsed.scheme } returns "file"
        every { parsed.path } returns SOURCE_PATH
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
        unmockkStatic(Uri::class)
    }

    @Test
    fun `given a DSD source when analysed then it is skipped without decoding`() = runTest {
        val sampler = FakeStationarySampler(measured())
        val analyser = analyser(sampler)

        val outcome = analyser.analyseIfNeeded(track(codec = AudioCodec.DSD_64))

        assertSkipped(TrackAnalysisOutcome.SkipReason.DSD_SOURCE, outcome)
        assertEquals(0, sampler.invocations)
    }

    @Test
    fun `given a track shorter than the minimum when analysed then it is skipped`() = runTest {
        val sampler = FakeStationarySampler(measured())
        val analyser = analyser(sampler)

        val outcome = analyser.analyseIfNeeded(track(durationMs = 2_500L))

        assertSkipped(TrackAnalysisOutcome.SkipReason.TOO_SHORT, outcome)
        assertEquals(0, sampler.invocations)
    }

    @Test
    fun `given a track with no content key when analysed then it is skipped`() = runTest {
        val sampler = FakeStationarySampler(measured())
        val analyser = analyser(sampler)

        val outcome = analyser.analyseIfNeeded(track(audioKey = ""))

        assertSkipped(TrackAnalysisOutcome.SkipReason.MISSING_AUDIO_KEY, outcome)
        assertEquals(0, sampler.invocations)
    }

    @Test
    fun `given a track already analysed at the current schema version then it is skipped`() =
        runTest {
            val sampler = FakeStationarySampler(measured())
            val analyser = analyser(sampler)
            analyser.analyseIfNeeded(track())

            val outcome = analyser.analyseIfNeeded(track())

            assertSkipped(TrackAnalysisOutcome.SkipReason.ALREADY_ANALYSED, outcome)
            assertEquals(1, sampler.invocations)
        }

    @Test
    fun `given a stale schema version on the cached row then the track is analysed again`() =
        runTest {
            val sampler = FakeStationarySampler(measured())
            repository.storeAtSchemaVersion(
                audioKey = AUDIO_KEY,
                stationary = FEATURES.toDomain(),
                schemaVersion = TrackAnalysis.SCHEMA_VERSION - 1,
            )

            val outcome = analyser(sampler).analyseIfNeeded(track())

            assertTrue(outcome.getOrNull() is TrackAnalysisOutcome.Analysed)
            assertEquals(1, sampler.invocations)
        }

    @Test
    fun `given a failed decode when analysed then the failure is returned not thrown`() = runTest {
        val decodeFailure = IllegalStateException("decoder blew up")
        val sampler = FakeStationarySampler(StationarySamplingResult.Failed(decodeFailure))

        val outcome = analyser(sampler).analyseIfNeeded(track())

        assertTrue(outcome.isFailure)
        assertEquals(decodeFailure, outcome.exceptionOrNull())
        assertNull(repository.stationaryFor(AUDIO_KEY))
    }

    @Test
    fun `given no measurement graph when analysed then it is skipped and nothing is cached`() =
        runTest {
            val sampler = FakeStationarySampler(StationarySamplingResult.Unavailable)

            val outcome = analyser(sampler).analyseIfNeeded(track())

            assertSkipped(TrackAnalysisOutcome.SkipReason.MEASUREMENT_UNAVAILABLE, outcome)
            assertNull(repository.stationaryFor(AUDIO_KEY))
        }

    @Test
    fun `given a pass that measured no window then the track is not cached as analysed`() =
        runTest {
            val empty = FEATURES.copy(windowCount = 0, frameCount = 0L)
            val sampler = FakeStationarySampler(StationarySamplingResult.Measured(empty))

            val outcome = analyser(sampler).analyseIfNeeded(track())

            assertTrue(outcome.isFailure)
            assertNull(repository.stationaryFor(AUDIO_KEY))
        }

    @Test
    fun `given a measurable track when analysed then the measurement is cached`() = runTest {
        val sampler = FakeStationarySampler(measured())

        val outcome = analyser(sampler).analyseIfNeeded(track())

        val analysed = outcome.getOrNull() as TrackAnalysisOutcome.Analysed
        assertEquals(FEATURES.toDomain(), analysed.stationary)
        assertEquals(FEATURES.toDomain(), repository.stationaryFor(AUDIO_KEY))
    }

    @Test
    fun `given two concurrent calls for the same track then only one decode runs`() = runTest {
        val sampler = FakeStationarySampler(measured())
        val analyser = analyser(sampler)

        val first = async { analyser.analyseIfNeeded(track()) }
        val second = async { analyser.analyseIfNeeded(track()) }
        val outcomes = listOf(first.await(), second.await())

        assertEquals(1, sampler.invocations)
        assertEquals(1, outcomes.count { it.getOrNull() is TrackAnalysisOutcome.Analysed })
        assertSkipped(
            TrackAnalysisOutcome.SkipReason.ALREADY_ANALYSED,
            outcomes.first { it.getOrNull() is TrackAnalysisOutcome.Skipped },
        )
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * @receiver Test scope whose scheduler the analyser's dispatcher shares, so a pass
     *   that hops dispatchers still advances with the test.
     * @param sampler Measurement pass the analyser should drive.
     * @param dispatcher Dispatcher standing in for `@IoDispatcher`.
     * @return An analyser wired to the shared fake repository.
     */
    private fun TestScope.analyser(
        sampler: StationarySampler,
        dispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
    ) = TrackSignalAnalyser(
        context = context,
        repository = repository,
        sampler = sampler,
        ioDispatcher = dispatcher,
    )

    /**
     * @param audioKey Content key to give the track.
     * @param durationMs Duration to declare.
     * @param codec Scan-time codec.
     * @return A track that passes every skip rule unless a parameter says otherwise.
     */
    private fun track(
        audioKey: String = AUDIO_KEY,
        durationMs: Long = 240_000L,
        codec: AudioCodec = AudioCodec.FLAC,
    ) = AnalysableTrack(
        audioKey = audioKey,
        uri = "file://$SOURCE_PATH",
        durationMs = durationMs,
        codec = codec,
    )

    /** @return A sampling result carrying the canonical measured feature set. */
    private fun measured() = StationarySamplingResult.Measured(FEATURES)

    /**
     * @param expected Skip reason the outcome should carry.
     * @param actual Outcome returned by the analyser.
     */
    private fun assertSkipped(
        expected: TrackAnalysisOutcome.SkipReason,
        actual: Result<TrackAnalysisOutcome>,
    ) {
        val outcome = actual.getOrNull()
        assertNotNull("Expected a successful skip, got $actual", outcome)
        assertEquals(expected, (outcome as TrackAnalysisOutcome.Skipped).reason)
    }

    private companion object {

        const val AUDIO_KEY = "audio-key-1"
        const val SOURCE_PATH = "/storage/music/track.flac"

        val FEATURES = AudioAnalysisFeatures(
            spectralRolloffHz = 20_500.0,
            spectralCentroidHz = 3_100.0,
            spectralSlope = -0.8,
            noiseFloorDbfs = -96.0,
            dcOffset = 0.0001,
            leftRmsDbfs = -14.0,
            rightRmsDbfs = -14.2,
            midRmsDbfs = -13.5,
            sideRmsDbfs = -22.0,
            interChannelCorrelation = 0.86,
            windowCount = 4,
            frameCount = 176_400L,
        )

        /** @return The same numbers in the shape the cache stores. */
        fun AudioAnalysisFeatures.toDomain() = StationaryAnalysis(
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
    }
}

/**
 * Measurement pass that answers with a canned result and counts how often it was asked.
 *
 * @property result Result every call returns.
 */
private class FakeStationarySampler(
    private val result: StationarySamplingResult,
) : StationarySampler {

    /** Number of passes actually run — the assertion that a skip really skipped. */
    var invocations: Int = 0
        private set

    override fun sample(sourcePath: String): StationarySamplingResult {
        invocations++
        return result
    }
}

/**
 * In-memory stand-in for the analysis cache.
 *
 * Real storage rather than a mock, because the behaviour under test — a second caller
 * finding the first caller's row — only exists if writes are actually readable.
 */
private class FakeTrackAnalysisRepository : TrackAnalysisRepository {

    private val rows = mutableMapOf<String, TrackAnalysis>()

    override suspend fun getAnalysis(audioKey: String): Resource<TrackAnalysis?> =
        Resource.Success(rows[audioKey]?.takeIf { it.schemaVersion == TrackAnalysis.SCHEMA_VERSION })

    /**
     * Unused here: the analyser addresses the cache by content key, never by track id.
     */
    override suspend fun getAnalysisForTrack(trackId: Long): Resource<TrackAnalysis?> =
        Resource.Success(null)

    override suspend fun saveStationaryAnalysis(
        audioKey: String,
        stationary: StationaryAnalysis,
    ): Resource<Unit> {
        storeAtSchemaVersion(audioKey, stationary, TrackAnalysis.SCHEMA_VERSION)
        return Resource.Success(Unit)
    }

    override suspend fun saveIntegralAnalysis(
        audioKey: String,
        integral: IntegralAnalysis,
    ): Resource<Unit> {
        val existing = rows[audioKey]
        rows[audioKey] = TrackAnalysis(
            audioKey = audioKey,
            schemaVersion = TrackAnalysis.SCHEMA_VERSION,
            analysedAtEpochSeconds = 0L,
            stationary = existing?.stationary,
            integral = integral,
        )
        return Resource.Success(Unit)
    }

    override suspend fun countMissingStationaryAnalysis(): Resource<Int> =
        Resource.Success(rows.count { it.value.stationary == null })

    override suspend fun countMissingIntegralAnalysis(): Resource<Int> =
        Resource.Success(rows.count { it.value.integral == null })

    /**
     * Seeds a row, optionally under a superseded schema version.
     *
     * @param audioKey Key to store under.
     * @param stationary Measurements to store.
     * @param schemaVersion Version to stamp on the row.
     */
    fun storeAtSchemaVersion(
        audioKey: String,
        stationary: StationaryAnalysis,
        schemaVersion: Int,
    ) {
        val existing = rows[audioKey]
        rows[audioKey] = TrackAnalysis(
            audioKey = audioKey,
            schemaVersion = schemaVersion,
            analysedAtEpochSeconds = 0L,
            stationary = stationary,
            integral = existing?.integral,
        )
    }

    /**
     * @param audioKey Key to read.
     * @return The stored stationary measurements, or `null` when none were written.
     */
    fun stationaryFor(audioKey: String): StationaryAnalysis? = rows[audioKey]?.stationary
}
