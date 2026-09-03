package com.androidexpert35.audiophilemusicplayer.data.playback.analysis

import android.content.Context
import android.util.Log
import com.androidexpert35.audiophilemusicplayer.data.mapper.toIntegralAnalysis
import com.androidexpert35.audiophilemusicplayer.data.playback.resolveUriToPath
import com.androidexpert35.audiophilemusicplayer.di.IoDispatcher
import com.androidexpert35.audiophilemusicplayer.domain.model.analysis.IntegralAnalysis
import com.androidexpert35.audiophilemusicplayer.domain.repository.TrackAnalysisRepository
import com.tony.coreui.domain.resource.Resource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides which tracks are worth a full-file loudness pass, measures them, and caches the
 * result.
 *
 * The integral counterpart of [TrackSignalAnalyser], and it exists for the tracks the
 * cheap route cannot reach. Where the Kotlin write loop already sees every sample the
 * same figures accumulate for free while a track plays; on the pure bit-perfect libusb
 * transport the native pump owns the data and Kotlin sees none of it, and a track the
 * user has never played has no listen to piggyback on. Those are this class's tracks.
 *
 * The rule it exists to enforce is the same as its sibling's: reading audio outside
 * playback must be invisible to playback. Every part of a pass — the `content://`
 * resolution, the decode, the filter graph, the database write — runs on [IoDispatcher].
 * Nothing here is reachable from
 * [com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile.BitPerfectPlaybackEngine]'s
 * `THREAD_PRIORITY_AUDIO` HandlerThread, and nothing here touches the engine, its
 * decoder, its sinks or its telemetry. No DSP behaviour changes because this class runs.
 *
 * ### What is skipped, and why
 *
 * A full decode is the most expensive background work in the app, so a pass is only spent
 * where its answer can be used:
 * - **DSD sources** bypass the DSP stage entirely on every bit-perfect transport.
 * - **Lossy sources, and sources already at native hi-res**, are not what the Hi-Res
 *   Remaster stage runs on, so nothing would ever read their loudness. This mirrors that
 *   gate exactly; see [isEligibleForIntegralAnalysis]. It can only be decided once the
 *   decoder is open, so it is reported by the pass rather than checked up front.
 * - **Tracks with no content key** have no address to store a result under.
 * - **Tracks already measured at the current schema version** are simply done.
 *
 * A skip is reported, not silently swallowed, so a caller sweeping the library can tell
 * "nothing to do here" from "this failed and may be worth retrying".
 *
 * ### Idempotence and concurrency
 *
 * [analyseIfNeeded] may be called repeatedly for the same track: once a result is cached
 * every later call skips. Concurrent calls are serialised on one lock and each re-reads
 * the cache after acquiring it, so a second caller that arrives while the first is
 * decoding finds the finished row and skips rather than decoding the same file twice.
 *
 * The lock is global rather than per track, and it matters more here than for the
 * stationary pass: a full-file decode holds an FFmpeg session, a filter graph and a
 * buffer for as long as the decode takes, so letting several run at once would compete
 * with playback for exactly the resources playback needs.
 *
 * @property context Application context, used only to resolve `content://` URIs.
 * @property repository Cache the measurements are read from and written to.
 * @property sampler Performs the full-file measurement pass for one source.
 * @property ioDispatcher Dispatcher every part of a pass runs on.
 */
@Singleton
class TrackIntegralAnalyser @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: TrackAnalysisRepository,
    private val sampler: IntegralSampler,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /** Serialises passes so only one full-file decode exists at a time. */
    private val passMutex = Mutex()

    /**
     * Measures [track] over its whole length unless there is a reason not to, and caches
     * what it measured.
     *
     * @param track The track to consider, carrying its content key, source URI and
     *   scan-time format.
     * @return The outcome — measured, or skipped with the reason — on success. A failure
     *   (unreadable source, failed decode, storage error) comes back as a failed [Result]
     *   and is never thrown at the caller.
     */
    suspend fun analyseIfNeeded(track: AnalysableTrack): Result<IntegralAnalysisOutcome> {
        // Cheap policy first: neither of these needs a lock, a decoder or a database read.
        when {
            track.audioKey.isBlank() ->
                return skipped(IntegralAnalysisOutcome.SkipReason.MISSING_AUDIO_KEY)
            track.isDsdSource ->
                return skipped(IntegralAnalysisOutcome.SkipReason.DSD_SOURCE)
        }

        return passMutex.withLock {
            // Re-read inside the lock: a concurrent caller for this same track may have
            // finished measuring it while this one was waiting.
            when (val cached = repository.getAnalysis(track.audioKey)) {
                is Resource.Success ->
                    if (cached.data?.integral != null) {
                        return@withLock skipped(
                            IntegralAnalysisOutcome.SkipReason.ALREADY_ANALYSED
                        )
                    }
                is Resource.Error ->
                    return@withLock Result.failure(
                        IllegalStateException(
                            "Could not read the analysis cache for ${track.audioKey}: ${cached.data}"
                        )
                    )
            }

            measureAndPersist(track)
        }
    }

    /**
     * Runs one full-file pass over [track] and stores what it produced.
     *
     * The decode is wrapped in a single [withContext] block containing no suspension
     * point, so the whole session — open, feed, read, close — runs start to finish on one
     * thread. That is the contract both the decoder and the measurement bridge require,
     * and it is why the persistence step sits outside the block.
     *
     * @param track Track that passed every cheap skip rule.
     * @return The outcome of the pass, or the failure that ended it.
     */
    private suspend fun measureAndPersist(
        track: AnalysableTrack
    ): Result<IntegralAnalysisOutcome> {
        val measured = try {
            withContext(ioDispatcher) {
                sampler.measure(resolveUriToPath(context, track.uri))
            }
        } catch (cancellation: CancellationException) {
            // Cancellation is the caller's sweep being torn down, not a measurement
            // failure — it has to keep propagating.
            throw cancellation
        } catch (failure: Exception) {
            return Result.failure(failure)
        }

        return when (measured) {
            is IntegralSamplingResult.Ineligible ->
                skipped(IntegralAnalysisOutcome.SkipReason.NOT_ELIGIBLE)

            is IntegralSamplingResult.Unavailable ->
                skipped(IntegralAnalysisOutcome.SkipReason.MEASUREMENT_UNAVAILABLE)

            is IntegralSamplingResult.Failed ->
                Result.failure(measured.cause)

            is IntegralSamplingResult.Measured ->
                if (measured.features.isEmpty) {
                    // The graph was built but no audio ever reached it. Persisting that
                    // would cache an all-null row and stop the track being retried.
                    Result.failure(
                        IllegalStateException("No audio could be measured for ${track.uri}")
                    )
                } else {
                    persist(
                        audioKey = track.audioKey,
                        integral = measured.features.toIntegralAnalysis(),
                        elapsedMillis = measured.elapsedMillis,
                    )
                }
        }
    }

    /**
     * Writes one measurement to the cache.
     *
     * @param audioKey Content key the measurement belongs to.
     * @param integral Measurements to store.
     * @param elapsedMillis Wall-clock cost of the pass that produced them.
     * @return The measured outcome, or the storage failure.
     */
    private suspend fun persist(
        audioKey: String,
        integral: IntegralAnalysis,
        elapsedMillis: Long,
    ): Result<IntegralAnalysisOutcome> =
        when (val stored = repository.saveIntegralAnalysis(audioKey, integral)) {
            is Resource.Success -> {
                Log.i(TAG, "Measured $audioKey over its full length in ${elapsedMillis}ms")
                Result.success(IntegralAnalysisOutcome.Analysed(integral, elapsedMillis))
            }
            is Resource.Error -> Result.failure(
                IllegalStateException("Could not cache the analysis of $audioKey: ${stored.data}")
            )
        }

    /**
     * @param reason Why the track was passed over.
     * @return A successful result carrying the skip; a skip is an outcome, not an error.
     */
    private fun skipped(
        reason: IntegralAnalysisOutcome.SkipReason
    ): Result<IntegralAnalysisOutcome> =
        Result.success(IntegralAnalysisOutcome.Skipped(reason))

    private companion object {

        const val TAG = "TrackIntegralAnalysis"
    }
}
