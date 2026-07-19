package com.androidexpert35.audiophilemusicplayer.data.repository

import com.androidexpert35.audiophilemusicplayer.data.local.dao.LibraryIndexDao
import com.androidexpert35.audiophilemusicplayer.data.remote.api.DeezerApiService
import com.androidexpert35.audiophilemusicplayer.data.remote.resolveDeezerArtistMatch
import com.androidexpert35.audiophilemusicplayer.di.IoDispatcher
import com.androidexpert35.audiophilemusicplayer.domain.model.track.isUnknownArtistName
import com.androidexpert35.audiophilemusicplayer.domain.repository.RemoteImageRepository
import com.tony.coreui.domain.resource.Resource
import com.tony.coreui.domain.resource.ResourceError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [RemoteImageRepository] implementation that combines a Room-backed cache with
 * live Deezer API lookups to serve artist images and album cover URLs.
 *
 * ### Caching strategy
 * 1. The local Room index is checked first; if a non-blank remote URL is already
 *    stored, it is returned immediately without touching the network.
 * 2. On an artist cache miss the Deezer API is queried and only an exact
 *    normalised-name candidate is accepted. When Deezer has multiple exact-name
 *    candidates, the profile with the highest follower count is selected.
 * 3. The highest-resolution URL (`picture_xl` / `cover_xl`, falling back to
 *    `picture_big` / `cover_big`) is extracted from the validated result.
 * 4. A successful URL is immediately written back to the matching Room row so
 *    that the next call is served from the local cache.
 * 5. When Deezer returns no exact match or the network call fails, a
 *    [ResourceError.NetworkError] is returned and **nothing is written to Room**
 *    — this ensures a future attempt can retry the network lookup.
 *
 * Per-artist mutexes collapse concurrent requests from the library list, search,
 * and detail screen into one cache/network operation.
 *
 * @property deezerApiService Retrofit client pointing to `https://api.deezer.com/`.
 * @property libraryIndexDao DAO for reading cached remote URLs and writing new ones.
 * @property ioDispatcher Injected IO dispatcher for all blocking work.
 * @constructor Created by Hilt via the [com.androidexpert35.audiophilemusicplayer.di.RepositoryModule].
 */
@Singleton
class RemoteImageRepositoryImpl @Inject constructor(
    private val deezerApiService: DeezerApiService,
    private val libraryIndexDao: LibraryIndexDao,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : RemoteImageRepository {

    private val artistLookupMutexes = ConcurrentHashMap<String, Mutex>()

    override suspend fun getArtistImageUrl(artistName: String): Resource<String> =
        withContext(ioDispatcher) {
            if (artistName.isUnknownArtistName()) {
                return@withContext Resource.Error(
                    ResourceError.NetworkError(
                        "Remote artwork is unavailable for an unknown artist"
                    )
                )
            }

            val lookupKey = artistName.trim().lowercase(Locale.ROOT)
            val lookupMutex = artistLookupMutexes.getOrPut(lookupKey) { Mutex() }

            lookupMutex.withLock {
                runCatching {
                    // Re-check Room after acquiring the per-artist lock because another
                    // screen may have populated the cache while this caller was waiting.
                    val cached = libraryIndexDao.getArtistByName(artistName)
                    val cachedUrl = cached?.remoteImageUrl
                    if (!cachedUrl.isNullOrBlank()) {
                        return@runCatching Resource.Success(cachedUrl)
                    }

                    val response = deezerApiService.searchArtist(artistName.trim())
                    val matchedArtist = resolveDeezerArtistMatch(artistName, response.data)
                    val imageUrl = matchedArtist?.pictureXl?.takeIf { it.isNotBlank() }
                        ?: matchedArtist?.pictureBig?.takeIf { it.isNotBlank() }

                    if (imageUrl != null) {
                        libraryIndexDao.updateArtistRemoteImageUrl(artistName, imageUrl)
                        Resource.Success(imageUrl)
                    } else {
                        Resource.Error(
                            ResourceError.NetworkError(
                                "No exact artist image match found on Deezer for \"$artistName\""
                            )
                        )
                    }
                }.getOrElse { throwable ->
                    Resource.Error(
                        ResourceError.NetworkError(
                            throwable.message ?: "Deezer artist search failed"
                        )
                    )
                }
            }
        }

    override suspend fun getAlbumArtUrl(albumTitle: String, artistName: String): Resource<String> =
        withContext(ioDispatcher) {
            runCatching {
                // 1. Check local cache — avoid network if already enriched
                val cached = libraryIndexDao.getAlbumByTitleAndArtist(albumTitle, artistName)
                val cachedUrl = cached?.remoteArtUrl
                if (!cachedUrl.isNullOrBlank()) {
                    return@runCatching Resource.Success(cachedUrl)
                }

                // 2. Compose a Deezer album query that narrows by both artist and title.
                //    Using the advanced Deezer query syntax (artist:"x" album:"y") yields
                //    significantly tighter results than a plain text query alone.
                val query = buildString {
                    if (artistName.isNotBlank()) append("artist:\"$artistName\" ")
                    append("album:\"$albumTitle\"")
                }

                val response = deezerApiService.searchAlbum(query)
                val coverUrl = response.data.firstOrNull()?.let { album ->
                    album.coverXl?.takeIf { it.isNotBlank() }
                        ?: album.coverBig?.takeIf { it.isNotBlank() }
                }

                if (coverUrl != null) {
                    // 3. Write back to Room so the next call is a cache hit
                    libraryIndexDao.updateAlbumRemoteArtUrl(albumTitle, artistName, coverUrl)
                    Resource.Success(coverUrl)
                } else {
                    Resource.Error(
                        ResourceError.NetworkError(
                            "No album cover found on Deezer for \"$albumTitle\" by \"$artistName\""
                        )
                    )
                }
            }.getOrElse { throwable ->
                Resource.Error(
                    ResourceError.NetworkError(
                        throwable.message ?: "Deezer album search failed"
                    )
                )
            }
        }
}
