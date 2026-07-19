package com.androidexpert35.audiophilemusicplayer.data.repository

import com.androidexpert35.audiophilemusicplayer.data.local.dao.LikedSongDao
import com.androidexpert35.audiophilemusicplayer.data.local.entity.LikedSongEntity
import com.androidexpert35.audiophilemusicplayer.di.IoDispatcher
import com.androidexpert35.audiophilemusicplayer.domain.repository.LikedSongsRepository
import com.tony.coreui.domain.resource.Resource
import com.tony.coreui.domain.resource.ResourceError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room-backed implementation of [LikedSongsRepository].
 *
 * All writes are dispatched on [IoDispatcher] to avoid blocking the main thread.
 * The reactive observation is backed by Room's built-in Flow invalidation, which
 * re-emits the liked IDs whenever the `liked_songs` table changes.
 *
 * @property likedSongDao DAO for the `liked_songs` table.
 * @property ioDispatcher Dispatcher for all blocking database operations.
 */
@Singleton
class LikedSongsRepositoryImpl @Inject constructor(
    private val likedSongDao: LikedSongDao,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : LikedSongsRepository {

    /**
     * @see LikedSongsRepository.observeLikedSongIds
     */
    override fun observeLikedSongIds(): Flow<Set<Long>> =
        likedSongDao.observeLikedSongIds()
            .map { ids -> ids.toSet() }
            .catch { emit(emptySet()) }
            .flowOn(ioDispatcher)

    /**
     * @see LikedSongsRepository.getLikedTrackIds
     */
    override suspend fun getLikedTrackIds(): Resource<Set<Long>> =
        withContext(ioDispatcher) {
            runCatching { likedSongDao.getLikedSongIds().toSet() }
                .fold(
                    onSuccess = { ids -> Resource.Success(ids) },
                    onFailure = { e ->
                        Resource.Error(
                            ResourceError.DatabaseError(
                                e.message ?: "Failed to read liked songs"
                            )
                        )
                    }
                )
        }

    /**
     * Toggles the liked status of [trackId].
     *
     * Reads the current liked state first so the toggle is always accurate even if
     * two coroutines race to call this concurrently — both reads happen inside the
     * same IO dispatcher context.
     *
     * @see LikedSongsRepository.toggleLike
     */
    override suspend fun toggleLike(trackId: Long): Resource<Unit> =
        withContext(ioDispatcher) {
            runCatching {
                if (likedSongDao.isLiked(trackId)) {
                    likedSongDao.unlikeSong(trackId)
                } else {
                    likedSongDao.likeSong(
                        LikedSongEntity(
                            trackId = trackId,
                            likedAt = System.currentTimeMillis()
                        )
                    )
                }
            }
                .fold(
                    onSuccess = { Resource.Success(Unit) },
                    onFailure = { e ->
                        Resource.Error(
                            ResourceError.DatabaseError(
                                e.message ?: "Failed to toggle like"
                            )
                        )
                    }
                )
        }
}

