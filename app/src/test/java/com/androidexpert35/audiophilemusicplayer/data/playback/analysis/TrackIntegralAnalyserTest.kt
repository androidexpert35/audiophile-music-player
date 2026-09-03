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
 * Behavioural tests for [TrackIntegralAnalyser].
 *
 * A full-file decode is the most expensive background work in the app, so what matters
 * about this component is what it refuses to do: it must not decode a track whose result
 * nothing will read, must not decode the same track twice, and must not let a broken file
 * reach the caller as an exception. Those are the cases here.
 *
 * The measurement pass itself is a fake. The real one owns an FFmpeg session and a native
 * filter graph and cannot exist on the JVM — which is exactly why the orchestrator talks
 * to it through [IntegralSampler] rather than building one itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrackIntegralAnalyserTest {

    private val repository = FakeIntegralAnalysisRepository()
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
        val sampler = FakeIntegralSampler(measured())
        val analyser = analyser(sampler)

        val outcome = analyser.analyseIfNeeded(track(codec = AudioCodec.DSD_64))

        assertSkipped(IntegralAnalysisOutcome.SkipReason.DSD_SOURCE, outcome)
        assertEquals(0, sampler.invocations)
    }

    @Test
    fun `given no content key when analysed then it is skipped without decoding`() = runTest {
        val sampler = FakeIntegralSampler(measured())
        val analyser = analyser(sampler)

        val outcome = analyser.analyseIfNeeded(track(audioKey = ""))

        assertSkipped(IntegralAnalysisOutcome.SkipReason.MISSING_AUDIO_KEY, outcome)
        assertEquals(0, sampler.invocations)
    }

    @Test
    fun `given an ineligible decoded format when analysed then nothing is cached`() = runTest {
        // Eligibility is only knowable once the decoder is open, so it comes back from
        // the pass. What matters is that the verdict is not mistaken for a failure and
        // that no half-measurement is stored.
        val analyser = analyser(FakeIntegralSampler(IntegralSamplingResult.Ineligible))

        val outcome = analyser.analyseIfNeeded(track())

        assertSkipped(IntegralAnalysisOutcome.SkipReason.NOT_ELIGIBLE, outcome)
        assertNull(repository.integralFor(AUDIO_KEY))
    }

    @Test
    fun `given no measurement graph when analysed then it is skipped not failed`() = runTest {
        val analyser = analyser(FakeIntegralSampler(IntegralSamplingResult.Unavailable))

        val outcome = analyser.analyseIfNeeded(track())

        assertSkipped(IntegralAnalysisOutcome.SkipReason.MEASUREMENT_UNAVAILABLE, outcome)
        assertNull(repository.integralFor(AUDIO_KEY))
    }

    @Test
    fun `given a broken source when analysed then the failure is returned not thrown`() =
        runTest {
            val cause = IllegalStateException("decoder refused the file")
            val analyser = analyser(FakeIntegralSampler(IntegralSamplingResult.Failed(cause)))

            val outcome = analyser.analyseIfNeeded(track())

            assertTrue(outcome.isFailure)
            assertEquals(cause, outcome.exceptionOrNull())
            assertNull(repository.integralFor(AUDIO_KEY))
        }

    @Test
    fun `given an empty aggregate when analysed then no row is cached`() = runTest {
        // A graph that was built but never fed produces an all-null aggregate. Caching it
        // would mark the track done and stop it ever being retried.
        val empty = IntegralSamplingResult.Measured(
            features = FEATURES.copy(frameCount = 0L),
            elapsedMillis = 12L,
            decodedFrames = 0L,
        )
        val analyser = analyser(FakeIntegralSampler(empty))

        val outcome = analyser.analyseIfNeeded(track())

        assertTrue(outcome.isFailure)
        assertNull(repository.integralFor(AUDIO_KEY))
    }

    @Test
    fun `given a measurable track when analysed then the result is cached with its cost`() =
        runTest {
            val analyser = analyser(FakeIntegralSampler(measured()))

            val outcome = analyser.analyseIfNeeded(track())

            val analysed = outcome.getOrNull() as IntegralAnalysisOutcome.Analysed
            assertEquals(ELAPSED_MS, analysed.elapsedMillis)
            assertEquals(FEATURES.samplePeakDbfs, analysed.integral.peakDbfs)
            assertEquals(FEATURES.integratedLufs, analysed.integral.integratedLufs)
            assertEquals(FEATURES.plrDb, analysed.integral.plr)
            assertEquals(FEATURES.clippingRatio, analysed.integral.clippingRatio)
            assertEquals(analysed.integral, repository.integralFor(AUDIO_KEY))
        }

    @Test
    fun `given a track already measured when analysed again then it is skipped`() = runTest {
        repository.saveIntegralAnalysis(AUDIO_KEY, CACHED)
        val sampler = FakeIntegralSampler(measured())
        val analyser = analyser(sampler)

        val outcome = analyser.analyseIfNeeded(track())

        assertSkipped(IntegralAnalysisOutcome.SkipReason.ALREADY_ANALYSED, outcome)
        assertEquals(0, sampler.invocations)
    }

    @Test
    fun `given a superseded schema version when analysed then it is measured again`() =
        runTest {
            repository.storeAtSchemaVersion(
                AUDIO_KEY,
                CACHED,
                TrackAnalysis.SCHEMA_VERSION - 1,
            )
            val sampler = FakeIntegralSampler(measured())
            val analyser = analyser(sampler)

            val outcome = analyser.analyseIfNeeded(track())

            assertTrue(outcome.getOrNull() is IntegralAnalysisOutcome.Analysed)
            assertEquals(1, sampler.invocations)
        }

    @Test
    fun `given two concurrent callers when analysed then only one decode happens`() = runTest {
        val sampler = FakeIntegralSampler(measured())
        val analyser = analyser(sampler)

        val first = async { analyser.analyseIfNeeded(track()) }
        val second = async { analyser.analyseIfNeeded(track()) }
        val outcomes = listOf(first.await(), second.await())

        assertEquals(1, sampler.invocations)
        assertEquals(1, outcomes.count { it.getOrNull() is IntegralAnalysisOutcome.Analysed })
        assertSkipped(
            IntegralAnalysisOutcome.SkipReason.ALREADY_ANALYSED,
            outcomes.first { it.getOrNull() is IntegralAnalysisOutcome.Skipped },
        )
    }

    @Test
    fun `given a cached stationary row when an integral pass runs then it survives`() =
        runTest {
            // The two classes are written by different passes at different times; one
            // must never erase the other.
            repository.storeStationary(AUDIO_KEY, STATIONARY)
            val analyser = analyser(FakeIntegralSampler(measured()))

            analyser.analyseIfNeeded(track())

            assertEquals(STATIONARY, repository.stationaryFor(AUDIO_KEY))
            assertNotNull(repository.integralFor(AUDIO_KEY))
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
        sampler: IntegralSampler,
        dispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
    ) = TrackIntegralAnalyser(
        context = context,
        repository = repository,
        sampler = sampler,
        ioDispatcher = dispatcher,
    )

    /**
     * @param audioKey Content key to give the track.
     * @param codec Scan-time codec.
     * @return A track that passes every cheap skip rule unless a parameter says otherwise.
     */
    private fun track(
        audioKey: String = AUDIO_KEY,
        codec: AudioCodec = AudioCodec.FLAC,
    ) = AnalysableTrack(
        audioKey = audioKey,
        uri = "file://$SOURCE_PATH",
        durationMs = 240_000L,
        codec = codec,
    )

    /** @return A pass result carrying the canonical measured feature set. */
    private fun measured() = IntegralSamplingResult.Measured(
        features = FEATURES,
        elapsedMillis = ELAPSED_MS,
        decodedFrames = FEATURES.frameCount,
    )

    /**
     * @param expected Skip reason the outcome should carry.
     * @param actual Outcome returned by the analyser.
     */
    private fun assertSkipped(
        expected: IntegralAnalysisOutcome.SkipReason,
        actual: Result<IntegralAnalysisOutcome>,
    ) {
        val outcome = actual.getOrNull()
        assertNotNull("Expected a successful skip, got $actual", outcome)
        assertEquals(expected, (outcome as IntegralAnalysisOutcome.Skipped).reason)
    }

    private companion object {

        const val AUDIO_KEY = "audio-key-1"
        const val SOURCE_PATH = "/storage/music/track.flac"
        const val ELAPSED_MS = 1_450L

        val FEATURES = AudioIntegralFeatures(
            samplePeakDbfs = -0.1,
            truePeakDbfs = 0.4,
            integratedLufs = -8.6,
            plrDb = 8.5,
            clippingRatio = 0.0012,
            flatRunCount = 91L,
            flatRunLongestSamples = 27.0,
            flatRunMeanSamples = 5.4,
            flatRunSampleRatio = 0.0007,
            frameCount = 10_584_000L,
        )

        val CACHED = IntegralAnalysis(
            peakDbfs = -1.0,
            integratedLufs = -12.0,
            plr = 11.0,
            clippingRatio = 0.0,
        )

        val STATIONARY = StationaryAnalysis(
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
    }
}

/**
 * Measurement pass that answers with a canned result and counts how often it was asked.
 *
 * @property result Result every call returns.
 */
private class FakeIntegralSampler(
    private val result: IntegralSamplingResult,
) : IntegralSampler {

    /** Number of passes actually run — the assertion that a skip really skipped. */
    var invocations: Int = 0
        private set

    override fun measure(sourcePath: String): IntegralSamplingResult {
        invocations++
        return result
    }
}

/**
 * In-memory stand-in for the analysis cache.
 *
 * Real storage rather than a mock, because two of the behaviours under test — a second
 * caller finding the first caller's row, and a stationary row surviving an integral write
 * — only exist if writes are actually readable.
 */
private class FakeIntegralAnalysisRepository : TrackAnalysisRepository {

    private val rows = mutableMapOf<String, TrackAnalysis>()

    override suspend fun getAnalysis(audioKey: String): Resource<TrackAnalysis?> =
        Resource.Success(rows[audioKey]?.takeIf { it.schemaVersion == TrackAnalysis.SCHEMA_VERSION })

    /** Unused here: the analyser addresses the cache by content key, never by track id. */
    override suspend fun getAnalysisForTrack(trackId: Long): Resource<TrackAnalysis?> =
        Resource.Success(null)

    override suspend fun saveStationaryAnalysis(
        audioKey: String,
        stationary: StationaryAnalysis,
    ): Resource<Unit> {
        storeStationary(audioKey, stationary)
        return Resource.Success(Unit)
    }

    override suspend fun saveIntegralAnalysis(
        audioKey: String,
        integral: IntegralAnalysis,
    ): Resource<Unit> {
        val existing = rows[audioKey]?.takeIf { it.schemaVersion == TrackAnalysis.SCHEMA_VERSION }
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
     * Seeds the stationary half of a row at the current schema version.
     *
     * @param audioKey Key to store under.
     * @param stationary Measurements to store.
     */
    fun storeStationary(audioKey: String, stationary: StationaryAnalysis) {
        val existing = rows[audioKey]
        rows[audioKey] = TrackAnalysis(
            audioKey = audioKey,
            schemaVersion = TrackAnalysis.SCHEMA_VERSION,
            analysedAtEpochSeconds = 0L,
            stationary = stationary,
            integral = existing?.integral,
        )
    }

    /**
     * Seeds an integral row under an arbitrary schema version.
     *
     * @param audioKey Key to store under.
     * @param integral Measurements to store.
     * @param schemaVersion Version to stamp on the row.
     */
    fun storeAtSchemaVersion(
        audioKey: String,
        integral: IntegralAnalysis,
        schemaVersion: Int,
    ) {
        rows[audioKey] = TrackAnalysis(
            audioKey = audioKey,
            schemaVersion = schemaVersion,
            analysedAtEpochSeconds = 0L,
            stationary = rows[audioKey]?.stationary,
            integral = integral,
        )
    }

    /**
     * @param audioKey Key to read.
     * @return The stored integral measurements, or `null` when none were written.
     */
    fun integralFor(audioKey: String): IntegralAnalysis? = rows[audioKey]?.integral

    /**
     * @param audioKey Key to read.
     * @return The stored stationary measurements, or `null` when none were written.
     */
    fun stationaryFor(audioKey: String): StationaryAnalysis? = rows[audioKey]?.stationary
}
