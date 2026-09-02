package com.androidexpert35.audiophilemusicplayer.data.playback.analysis

import android.content.Context
import android.util.Log
import com.androidexpert35.audiophilemusicplayer.data.mapper.toStationaryAnalysis
import com.androidexpert35.audiophilemusicplayer.data.playback.analysis.TrackSignalAnalyser.Companion.MIN_ANALYSABLE_DURATION_MS
import com.androidexpert35.audiophilemusicplayer.data.playback.resolveUriToPath
import com.androidexpert35.audiophilemusicplayer.di.IoDispatcher
import com.androidexpert35.audiophilemusicplayer.domain.model.analysis.StationaryAnalysis
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
 * Decides which tracks are worth measuring, measures them, and caches the result.
 *
 * This is the component that actually reads audio outside playback, and the rule it
 * exists to enforce is that doing so must be invisible to playback. Every part of a pass
 * — the `content://` resolution, the decode, the filter graph, the database write — runs
 * on [IoDispatcher]. Nothing here is reachable from
 * [com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile.BitPerfectPlaybackEngine]'s
 * `THREAD_PRIORITY_AUDIO` HandlerThread, and nothing here touches the engine, its
 * decoder, its sinks or its telemetry. No DSP behaviour changes because this class runs.
 *
 * ### What is skipped, and why
 *
 * Measuring is cheap per track and expensive per library, so a pass is only spent where
 * its answer can be used:
 * - **DSD sources** bypass the DSP stage entirely on every bit-perfect transport, so a
 *   measurement of one would be read by nobody.
 * - **Tracks under [MIN_ANALYSABLE_DURATION_MS]** cannot yield windows clear of their own
 *   fades.
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
 * The lock is deliberately global rather than per track. A pass holds an FFmpeg session,
 * a filter graph and a window buffer of up to a couple of megabytes; letting an unbounded
 * number of them run at once is a worse failure than making a background sweep strictly
 * sequential, which is how it is meant to run anyway.
 *
 * @property context Application context, used only to resolve `content://` URIs.
 * @property repository Cache the measurements are read from and written to.
 * @property sampler Performs the decode-and-measure pass for one source.
 * @property ioDispatcher Dispatcher every part of a pass runs on.
 */
@Singleton
class TrackSignalAnalyser @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: TrackAnalysisRepository,
    private val sampler: StationarySampler,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /** Serialises passes so only one decode session exists at a time. */
    private val passMutex = Mutex()

    /**
     * Measures [track] unless there is a reason not to, and caches what it measured.
     *
     * @param track The track to consider, carrying its content key, source URI and
     *   scan-time format.
     * @return The outcome — measured, or skipped with the reason — on success. A failure
     *   (unreadable source, failed decode, storage error) comes back as a failed
     *   [Result] and is never thrown at the caller.
     */
    suspend fun analyseIfNeeded(track: AnalysableTrack): Result<TrackAnalysisOutcome> {
        // Cheap policy first: none of these need a lock, a decoder or a database read.
        when {
            track.audioKey.isBlank() ->
                return skipped(TrackAnalysisOutcome.SkipReason.MISSING_AUDIO_KEY)
            track.isDsdSource ->
                return skipped(TrackAnalysisOutcome.SkipReason.DSD_SOURCE)
            track.durationMs < MIN_ANALYSABLE_DURATION_MS ->
                return skipped(TrackAnalysisOutcome.SkipReason.TOO_SHORT)
        }

        return passMutex.withLock {
            // Re-read inside the lock: a concurrent caller for this same track may have
            // finished measuring it while this one was waiting.
            when (val cached = repository.getAnalysis(track.audioKey)) {
                is Resource.Success ->
                    if (cached.data?.stationary != null) {
                        return@withLock skipped(TrackAnalysisOutcome.SkipReason.ALREADY_ANALYSED)
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
     * Runs one pass over [track] and stores what it produced.
     *
     * The decode is wrapped in a single [withContext] block containing no suspension
     * point, so the whole session — open, seek, feed, read, close — runs start to finish
     * on one thread. That is the contract both the decoder and the measurement bridge
     * require, and it is why the persistence step sits outside the block.
     *
     * @param track Track that passed every skip rule.
     * @return The outcome of the pass, or the failure that ended it.
     */
    private suspend fun measureAndPersist(track: AnalysableTrack): Result<TrackAnalysisOutcome> {
        val sampled = try {
            withContext(ioDispatcher) {
                sampler.sample(resolveUriToPath(context, track.uri))
            }
        } catch (cancellation: CancellationException) {
            // Cancellation is the caller's sweep being torn down, not a measurement
            // failure — it has to keep propagating.
            throw cancellation
        } catch (failure: Exception) {
            return Result.failure(failure)
        }

        return when (sampled) {
            is StationarySamplingResult.DsdSource ->
                skipped(TrackAnalysisOutcome.SkipReason.DSD_SOURCE)

            is StationarySamplingResult.Unavailable ->
                skipped(TrackAnalysisOutcome.SkipReason.MEASUREMENT_UNAVAILABLE)

            is StationarySamplingResult.Failed ->
                Result.failure(sampled.cause)

            is StationarySamplingResult.Measured ->
                if (sampled.features.isEmpty) {
                    // The graph was built but no window ever reached it. Persisting that
                    // would cache an all-null row and stop the track being retried.
                    Result.failure(
                        IllegalStateException("No audio window could be measured for ${track.uri}")
                    )
                } else {
                    persist(track.audioKey, sampled.features.toStationaryAnalysis())
                }
        }
    }

    /**
     * Writes one measurement to the cache.
     *
     * @param audioKey Content key the measurement belongs to.
     * @param stationary Measurements to store.
     * @return The measured outcome, or the storage failure.
     */
    private suspend fun persist(
        audioKey: String,
        stationary: StationaryAnalysis,
    ): Result<TrackAnalysisOutcome> =
        when (val stored = repository.saveStationaryAnalysis(audioKey, stationary)) {
            is Resource.Success -> {
                Log.i(TAG, "Measured $audioKey over ${stationary.windowCount} window(s)")
                Result.success(TrackAnalysisOutcome.Analysed(stationary))
            }
            is Resource.Error -> Result.failure(
                IllegalStateException("Could not cache the analysis of $audioKey: ${stored.data}")
            )
        }

    /**
     * @param reason Why the track was passed over.
     * @return A successful result carrying the skip; a skip is an outcome, not an error.
     */
    private fun skipped(reason: TrackAnalysisOutcome.SkipReason): Result<TrackAnalysisOutcome> =
        Result.success(TrackAnalysisOutcome.Skipped(reason))

    private companion object {

        const val TAG = "TrackSignalAnalysis"

        /**
         * Shortest track worth measuring. Below this there is no room for sample windows
         * that are clear of the track's own start and end.
         */
        const val MIN_ANALYSABLE_DURATION_MS = 3_000L
    }
}
