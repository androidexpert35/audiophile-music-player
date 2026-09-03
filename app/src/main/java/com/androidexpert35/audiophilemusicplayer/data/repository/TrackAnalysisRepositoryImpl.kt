package com.androidexpert35.audiophilemusicplayer.data.repository

import com.androidexpert35.audiophilemusicplayer.data.local.dao.LibraryIndexDao
import com.androidexpert35.audiophilemusicplayer.data.local.dao.TrackAnalysisDao
import com.androidexpert35.audiophilemusicplayer.data.local.entity.TrackAnalysisEntity
import com.androidexpert35.audiophilemusicplayer.data.mapper.mergeIntegralAnalysis
import com.androidexpert35.audiophilemusicplayer.data.mapper.mergeStationaryAnalysis
import com.androidexpert35.audiophilemusicplayer.data.mapper.toDomain
import com.androidexpert35.audiophilemusicplayer.di.IoDispatcher
import com.androidexpert35.audiophilemusicplayer.domain.model.analysis.IntegralAnalysis
import com.androidexpert35.audiophilemusicplayer.domain.model.analysis.StationaryAnalysis
import com.androidexpert35.audiophilemusicplayer.domain.model.analysis.TrackAnalysis
import com.androidexpert35.audiophilemusicplayer.domain.repository.TrackAnalysisRepository
import com.tony.coreui.domain.resource.Resource
import com.tony.coreui.domain.resource.ResourceError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists measured signal analysis in Room, one row per piece of audio.
 *
 * Reads and writes run on [IoDispatcher]; failures become [Resource.Error] rather than
 * exceptions, because an unavailable measurement must degrade a DSP decision, never break
 * playback.
 *
 * Writing one measurement class is a read-modify-write — the row carries both classes and
 * only one of them is being replaced — so the two writers are serialised through a
 * [Mutex]. Without it the Class S pass finishing while the Class I pass commits would let
 * one of them persist a row built from a snapshot the other had already superseded, and
 * the loser's columns would silently revert to `null`. The lock is held only around the
 * paired DAO calls.
 *
 * @property trackAnalysisDao DAO for the `track_analysis` table.
 * @property libraryIndexDao DAO used only to translate a track id into the content key
 *   its file currently has, for callers that follow playback rather than audio.
 * @property ioDispatcher Dispatcher for all blocking database operations.
 */
@Singleton
class TrackAnalysisRepositoryImpl @Inject constructor(
    private val trackAnalysisDao: TrackAnalysisDao,
    private val libraryIndexDao: LibraryIndexDao,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : TrackAnalysisRepository {

    /** Serialises the read-modify-write cycle shared by both measurement classes. */
    private val writeMutex = Mutex()

    /**
     * @see TrackAnalysisRepository.getAnalysis
     */
    override suspend fun getAnalysis(audioKey: String): Resource<TrackAnalysis?> {
        if (audioKey.isBlank()) return Resource.Success(null)

        return withContext(ioDispatcher) {
            runCatching {
                trackAnalysisDao.getByAudioKey(audioKey)?.toDomain(TrackAnalysis.SCHEMA_VERSION)
            }.fold(
                onSuccess = { Resource.Success(it) },
                onFailure = { it.toDatabaseError("Failed to read the analysis cache") }
            )
        }
    }

    /**
     * @see TrackAnalysisRepository.getAnalysisForTrack
     */
    override suspend fun getAnalysisForTrack(trackId: Long): Resource<TrackAnalysis?> =
        withContext(ioDispatcher) {
            runCatching {
                // An unindexed track and a track indexed before its file could be
                // sampled are the same answer here: there is no key to look a
                // measurement up by, so there is no measurement.
                val audioKey = libraryIndexDao.getAudioKeyForTrack(trackId).orEmpty()
                if (audioKey.isBlank()) {
                    null
                } else {
                    trackAnalysisDao.getByAudioKey(audioKey)
                        ?.toDomain(TrackAnalysis.SCHEMA_VERSION)
                }
            }.fold(
                onSuccess = { Resource.Success(it) },
                onFailure = { it.toDatabaseError("Failed to read the analysis cache") }
            )
        }

    /**
     * @see TrackAnalysisRepository.saveStationaryAnalysis
     */
    override suspend fun saveStationaryAnalysis(
        audioKey: String,
        stationary: StationaryAnalysis
    ): Resource<Unit> = upsertMerged(audioKey, "stationary") { existing, nowEpochSeconds ->
        mergeStationaryAnalysis(
            existing = existing,
            audioKey = audioKey,
            stationary = stationary,
            schemaVersion = TrackAnalysis.SCHEMA_VERSION,
            nowEpochSeconds = nowEpochSeconds,
        )
    }

    /**
     * @see TrackAnalysisRepository.saveIntegralAnalysis
     */
    override suspend fun saveIntegralAnalysis(
        audioKey: String,
        integral: IntegralAnalysis
    ): Resource<Unit> = upsertMerged(audioKey, "integral") { existing, nowEpochSeconds ->
        mergeIntegralAnalysis(
            existing = existing,
            audioKey = audioKey,
            integral = integral,
            schemaVersion = TrackAnalysis.SCHEMA_VERSION,
            nowEpochSeconds = nowEpochSeconds,
        )
    }

    /**
     * @see TrackAnalysisRepository.countMissingStationaryAnalysis
     */
    override suspend fun countMissingStationaryAnalysis(): Resource<Int> =
        withContext(ioDispatcher) {
            runCatching { trackAnalysisDao.countMissingStationary(TrackAnalysis.SCHEMA_VERSION) }
                .fold(
                    onSuccess = { Resource.Success(it) },
                    onFailure = { it.toDatabaseError("Failed to count unanalysed stationary rows") }
                )
        }

    /**
     * @see TrackAnalysisRepository.countMissingIntegralAnalysis
     */
    override suspend fun countMissingIntegralAnalysis(): Resource<Int> =
        withContext(ioDispatcher) {
            runCatching { trackAnalysisDao.countMissingIntegral(TrackAnalysis.SCHEMA_VERSION) }
                .fold(
                    onSuccess = { Resource.Success(it) },
                    onFailure = { it.toDatabaseError("Failed to count unanalysed integral rows") }
                )
        }

    /**
     * Applies one measurement class to the stored row without disturbing the other.
     *
     * @param audioKey Content key of the measured audio; blank keys are not analysable
     *   and are rejected rather than stored under an unusable identifier.
     * @param className Measurement class name, used only in the failure message.
     * @param merge Builds the row to store from the row currently held and the write
     *   timestamp.
     * @return [Resource.Success] once the row is written, [Resource.Error] otherwise.
     */
    private suspend fun upsertMerged(
        audioKey: String,
        className: String,
        merge: (existing: TrackAnalysisEntity?, nowEpochSeconds: Long) -> TrackAnalysisEntity
    ): Resource<Unit> {
        if (audioKey.isBlank()) {
            return Resource.Error(
                ResourceError.LogicError("Cannot analyse audio without a content key.")
            )
        }

        return withContext(ioDispatcher) {
            runCatching {
                writeMutex.withLock {
                    val existing = trackAnalysisDao.getByAudioKey(audioKey)
                    trackAnalysisDao.upsert(merge(existing, nowEpochSeconds()))
                }
            }.fold(
                onSuccess = { Resource.Success(Unit) },
                onFailure = { it.toDatabaseError("Failed to store the $className analysis") }
            )
        }
    }

    /** @return Epoch seconds used to stamp a write. */
    private fun nowEpochSeconds(): Long = System.currentTimeMillis() / MILLIS_PER_SECOND

    /**
     * Wraps a storage failure as a domain error.
     *
     * @receiver Throwable raised by the DAO.
     * @param fallbackMessage Message used when the throwable carries none.
     * @return The failure as a [Resource.Error].
     */
    private fun <T> Throwable.toDatabaseError(fallbackMessage: String): Resource<T> =
        Resource.Error(ResourceError.DatabaseError(message ?: fallbackMessage))

    private companion object {
        /** Divisor turning the platform's millisecond clock into the stored epoch seconds. */
        const val MILLIS_PER_SECOND = 1_000L
    }
}
