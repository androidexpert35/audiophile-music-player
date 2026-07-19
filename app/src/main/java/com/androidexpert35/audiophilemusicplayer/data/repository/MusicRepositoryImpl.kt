package com.androidexpert35.audiophilemusicplayer.data.repository

import com.androidexpert35.audiophilemusicplayer.data.local.dao.LibraryIndexDao
import com.androidexpert35.audiophilemusicplayer.data.mapper.toDomainAlbum
import com.androidexpert35.audiophilemusicplayer.data.mapper.toDomainArtist
import com.androidexpert35.audiophilemusicplayer.data.mapper.toDomainTrack
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Album
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Artist
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track
import com.androidexpert35.audiophilemusicplayer.domain.repository.MusicRepository
import com.tony.coreui.domain.resource.Resource
import com.tony.coreui.domain.resource.ResourceError
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [MusicRepository] implementation backed by the Room-indexed local library cache.
 *
 * Reads are served from [LibraryIndexDao] so library surfaces remain responsive after the
 * initial MediaStore scan has populated the cache during onboarding.
 *
 * @property libraryIndexDao DAO for indexed-library reads.
 */
@Singleton
class MusicRepositoryImpl @Inject constructor(
    private val libraryIndexDao: LibraryIndexDao
) : MusicRepository {

    override suspend fun getTracks(): Resource<List<Track>> = runCatching {
        Resource.Success(libraryIndexDao.getTracks().map { it.toDomainTrack() })
    }.getOrElse { throwable ->
        Resource.Error(
            ResourceError.DatabaseError(throwable.message ?: "Failed to read indexed tracks")
        )
    }

    override suspend fun getAlbums(): Resource<List<Album>> = runCatching {
        Resource.Success(libraryIndexDao.getAlbums().map { it.toDomainAlbum() })
    }.getOrElse { throwable ->
        Resource.Error(
            ResourceError.DatabaseError(throwable.message ?: "Failed to read indexed albums")
        )
    }

    override suspend fun getArtists(): Resource<List<Artist>> = runCatching {
        Resource.Success(libraryIndexDao.getArtists().map { it.toDomainArtist() })
    }.getOrElse { throwable ->
        Resource.Error(
            ResourceError.DatabaseError(throwable.message ?: "Failed to read indexed artists")
        )
    }

    override suspend fun searchTracks(query: String): Resource<List<Track>> = runCatching {
        if (query.isBlank()) {
            Resource.Success(emptyList())
        } else {
            Resource.Success(libraryIndexDao.searchTracks(query).map { it.toDomainTrack() })
        }
    }.getOrElse { throwable ->
        Resource.Error(
            ResourceError.DatabaseError(throwable.message ?: "Failed to search indexed tracks")
        )
    }
}

