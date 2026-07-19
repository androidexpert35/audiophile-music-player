package com.androidexpert35.audiophilemusicplayer.data.repository

import com.androidexpert35.audiophilemusicplayer.data.local.dao.RecentlyPlayedDao
import com.androidexpert35.audiophilemusicplayer.di.IoDispatcher
import com.androidexpert35.audiophilemusicplayer.domain.repository.RecentlyPlayedRepository
import com.tony.coreui.domain.resource.Resource
import com.tony.coreui.domain.resource.ResourceError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists playback recency and personal play counts in Room.
 *
 * Writes are dispatched on [IoDispatcher] to avoid blocking the main thread.
 * The reactive observation relies on Room's built-in Flow invalidation, which
 * re-emits whenever any row in `recently_played` is inserted or updated.
 *
 * @property recentlyPlayedDao DAO for the `recently_played` table.
 * @property ioDispatcher Dispatcher for all blocking database operations.
 */
@Singleton
class RecentlyPlayedRepositoryImpl @Inject constructor(
    private val recentlyPlayedDao: RecentlyPlayedDao,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : RecentlyPlayedRepository {

    /**
     * @see RecentlyPlayedRepository.observeRecentlyPlayedTrackIds
     */
    override fun observeRecentlyPlayedTrackIds(limit: Int): Flow<List<Long>> =
        recentlyPlayedDao.observeRecentlyPlayedTrackIds(limit)
            .catch { emit(emptyList()) }
            .flowOn(ioDispatcher)

    /**
     * @see RecentlyPlayedRepository.observeMostPlayedTrackIds
     */
    override fun observeMostPlayedTrackIds(
        trackIds: List<Long>,
        limit: Int
    ): Flow<List<Long>> {
        if (trackIds.isEmpty() || limit <= 0) return kotlinx.coroutines.flow.flowOf(emptyList())

        return recentlyPlayedDao.observeMostPlayedTrackIds(trackIds, limit)
            .catch { emit(emptyList()) }
            .flowOn(ioDispatcher)
    }

    /**
     * Atomically increments the aggregate count and refreshes recency for [trackId].
     *
     * The DAO owns the conflict-update statement so concurrent events cannot lose
     * an increment through a read-modify-write race.
     *
     * @see RecentlyPlayedRepository.recordPlayed
     */
    override suspend fun recordPlayed(trackId: Long): Resource<Unit> =
        withContext(ioDispatcher) {
            runCatching {
                recentlyPlayedDao.recordPlaybackStart(
                    trackId = trackId,
                    playedAt = System.currentTimeMillis()
                )
            }
                .fold(
                    onSuccess = { Resource.Success(Unit) },
                    onFailure = { e ->
                        Resource.Error(
                            ResourceError.DatabaseError(
                                e.message ?: "Failed to record recently played"
                            )
                        )
                    }
                )
        }
}
