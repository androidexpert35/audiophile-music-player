package com.androidexpert35.audiophilemusicplayer.data.repository

import com.androidexpert35.audiophilemusicplayer.data.local.dao.LibraryIndexDao
import com.androidexpert35.audiophilemusicplayer.data.local.dao.PlaybackStateDao
import com.androidexpert35.audiophilemusicplayer.data.mapper.toDomain
import com.androidexpert35.audiophilemusicplayer.data.mapper.toDomainTrack
import com.androidexpert35.audiophilemusicplayer.data.mapper.toEntity
import com.androidexpert35.audiophilemusicplayer.domain.model.playback.PersistedPlaybackState
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track
import com.androidexpert35.audiophilemusicplayer.domain.repository.PlaybackPersistenceRepository
import com.tony.coreui.domain.resource.Resource
import com.tony.coreui.domain.resource.ResourceError
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [PlaybackPersistenceRepository] implementation backed by Room DAOs.
 *
 * All database operations are [suspend] functions; Room automatically dispatches
 * them off the main thread onto its internal thread pool, keeping the caller's
 * coroutine context free of explicit dispatcher switching.
 *
 * @property playbackStateDao DAO owning the singleton playback-session row.
 * @property libraryIndexDao DAO used to look up track metadata by ID during restoration.
 */
@Singleton
class PlaybackPersistenceRepositoryImpl @Inject constructor(
    private val playbackStateDao: PlaybackStateDao,
    private val libraryIndexDao: LibraryIndexDao
) : PlaybackPersistenceRepository {

    override suspend fun savePlaybackState(state: PersistedPlaybackState): Resource<Unit> =
        runCatching {
            playbackStateDao.upsertPlaybackState(state.toEntity())
            Resource.Success(Unit)
        }.getOrElse { throwable ->
            Resource.Error(
                ResourceError.DatabaseError(
                    throwable.message ?: "Failed to save playback state"
                )
            )
        }

    override suspend fun restorePlaybackState(): Resource<PersistedPlaybackState?> =
        runCatching {
            Resource.Success(playbackStateDao.getPlaybackState()?.toDomain())
        }.getOrElse { throwable ->
            Resource.Error(
                ResourceError.DatabaseError(
                    throwable.message ?: "Failed to restore playback state"
                )
            )
        }

    /**
     * Fetches [Track] domain models for the given ID list, preserving [ids] ordering.
     *
     * Room's `IN` clause does not guarantee result ordering, so returned entities are
     * re-sorted against the original [ids] sequence before mapping to domain models.
     * Tracks missing from the index (e.g. deleted files) are silently dropped.
     */
    override suspend fun getTracksForQueue(ids: List<Long>): Resource<List<Track>> {
        if (ids.isEmpty()) return Resource.Success(emptyList())
        return runCatching {
            val entities = libraryIndexDao.getTracksByIds(ids)

            // Rebuild the saved order — Room IN-clause results can arrive in any sequence
            val indexByIdMap: Map<Long, Int> = ids.withIndex().associate { (index, id) -> id to index }
            entities
                .sortedBy { entity -> indexByIdMap[entity.id] ?: Int.MAX_VALUE }
                .map { entity -> entity.toDomainTrack() }
        }.fold(
            onSuccess = { tracks -> Resource.Success(tracks) },
            onFailure = { throwable ->
                Resource.Error(
                    ResourceError.DatabaseError(
                        throwable.message ?: "Failed to fetch queue tracks"
                    )
                )
            }
        )
    }
}

