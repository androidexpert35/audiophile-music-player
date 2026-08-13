package com.androidexpert35.audiophilemusicplayer.data.repository

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.data.local.dao.ImportedPlaylistDao
import com.androidexpert35.audiophilemusicplayer.data.local.dao.LibraryIndexDao
import com.androidexpert35.audiophilemusicplayer.data.local.dao.LikedSongDao
import com.androidexpert35.audiophilemusicplayer.data.local.entity.ImportedPlaylistEntity
import com.androidexpert35.audiophilemusicplayer.data.local.entity.LikedSongEntity
import com.androidexpert35.audiophilemusicplayer.di.IoDispatcher
import com.androidexpert35.audiophilemusicplayer.domain.model.library.Playlist
import com.androidexpert35.audiophilemusicplayer.domain.model.library.PlaylistKind
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track
import com.androidexpert35.audiophilemusicplayer.domain.repository.LikedSongsRepository
import com.androidexpert35.audiophilemusicplayer.domain.repository.PlaylistRepository
import com.tony.coreui.domain.resource.Resource
import com.tony.coreui.domain.resource.ResourceError
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns every local M3U playlist and keeps the reserved favorites playlist aligned with Room.
 *
 * Regular playlists remain file-backed, while liked membership remains queryable through Room
 * for efficient heart-state observation. Implementing both repository contracts here gives the
 * favorites collection one coordinator, so a like, playlist-picker addition, or detail-page edit
 * updates both stores under the same serialized mutation.
 *
 * Playlists discovered inside a user-granted music folder (`.m3u`/`.m3u8` files found by
 * [com.androidexpert35.audiophilemusicplayer.data.scanner.M3uFileScanner]) are merged in
 * as [PlaylistKind.IMPORTED]. Unlike the app-private collection, mutating one of these
 * writes straight back to its original document via the SAF permission already granted on
 * its parent tree, instead of an app-private copy.
 *
 * @property context Application context used to resolve private playlist storage and its title.
 * @property likedSongDao Reactive liked-membership persistence.
 * @property libraryIndexDao Indexed tracks used to map stable IDs and content URIs.
 * @property importedPlaylistDao Cached `.m3u` playlists discovered in granted folders.
 * @property contentResolver Used to read/write/delete imported playlist documents via SAF.
 * @property ioDispatcher Dispatcher for filesystem and Room work.
 */
@Singleton
class PlaylistRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val likedSongDao: LikedSongDao,
    private val libraryIndexDao: LibraryIndexDao,
    private val importedPlaylistDao: ImportedPlaylistDao,
    private val contentResolver: ContentResolver,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : PlaylistRepository, LikedSongsRepository {

    private val playlistRevision = MutableStateFlow(0L)
    private val mutationMutex = Mutex()

    /** @see PlaylistRepository.observePlaylists */
    override fun observePlaylists(): Flow<List<Playlist>> = combine(
        playlistRevision,
        likedSongDao.observeLikedSongIds(),
        importedPlaylistDao.observeAll(),
    ) { _, likedIds, importedEntities ->
        mutationMutex.withLock {
            synchronizeFavoritesFile(likedIds)
            (loadPlaylists() + importedEntities.map(::toImportedPlaylist)).sortedWith(PLAYLIST_ORDER)
        }
    }
        .catch { emit(runCatching(::loadPlaylists).getOrDefault(emptyList())) }
        .flowOn(ioDispatcher)

    /** @see PlaylistRepository.createPlaylist */
    override suspend fun createPlaylist(name: String): Resource<Playlist> = withContext(ioDispatcher) {
        mutationMutex.withLock {
            val normalizedName = name.trim()
            val currentPlaylists = loadPlaylists()
            val importedNames = importedPlaylistDao.getAll().map(ImportedPlaylistEntity::name)
            when {
                normalizedName.isBlank() -> Resource.Error(
                    ResourceError.LogicError("A playlist name is required.")
                )

                normalizedName.equals(favoritesName(), ignoreCase = true) ||
                    currentPlaylists.any { it.name.equals(normalizedName, ignoreCase = true) } ||
                    importedNames.any { it.equals(normalizedName, ignoreCase = true) } -> Resource.Error(
                    ResourceError.LogicError("A playlist with this name already exists.")
                )

                else -> runCatching {
                    val id = "${UUID.randomUUID()}.$M3U_EXTENSION"
                    val playlist = Playlist(id = id, name = normalizedName, trackUris = emptyList())
                    writePlaylist(playlistDirectory().resolve(id), playlist.name, emptyList())
                    playlist
                }.fold(
                    onSuccess = { playlist ->
                        publishPlaylistChange()
                        Resource.Success(playlist)
                    },
                    onFailure = { error -> Resource.Error(storageError(error)) }
                )
            }
        }
    }

    /** @see PlaylistRepository.addTrack */
    override suspend fun addTrack(playlistId: String, track: Track): Resource<Unit> =
        addTracks(playlistId, listOf(track))

    /** @see PlaylistRepository.addTracks */
    override suspend fun addTracks(
        playlistId: String,
        tracks: List<Track>
    ): Resource<Unit> = withContext(ioDispatcher) {
        mutationMutex.withLock {
            runCatching {
                require(tracks.isNotEmpty()) { "Select at least one track to add." }
                if (playlistId == FAVORITES_PLAYLIST_ID) {
                    synchronizeFavoritesFile(likedSongDao.getLikedSongIds())
                    appendFavorites(tracks)
                } else {
                    val playlist = requirePlaylist(playlistId)
                    val newTrackUris = playlist.trackUris + tracks.map(Track::uri)
                    if (playlist.kind == PlaylistKind.IMPORTED) {
                        writeImportedPlaylist(playlist.id, playlist.name, newTrackUris)
                    } else {
                        writePlaylist(playlistDirectory().resolve(playlist.id), playlist.name, newTrackUris)
                    }
                }
            }.fold(
                onSuccess = {
                    publishPlaylistChange()
                    Resource.Success(Unit)
                },
                onFailure = { error -> Resource.Error(playlistMutationError(playlistId, error)) }
            )
        }
    }

    /** @see PlaylistRepository.reorderTracks */
    override suspend fun reorderTracks(playlistId: String, trackUris: List<String>): Resource<Unit> =
        withContext(ioDispatcher) {
            mutationMutex.withLock {
                runCatching {
                    if (playlistId == FAVORITES_PLAYLIST_ID) {
                        synchronizeFavoritesFile(likedSongDao.getLikedSongIds())
                    }
                    val playlist = requirePlaylist(playlistId)
                    check(
                        playlist.trackUris.groupingBy { it }.eachCount() ==
                            trackUris.groupingBy { it }.eachCount()
                    ) { "The playlist entries changed before their order could be saved." }
                    replacePlaylistContents(playlist, trackUris)
                }.fold(
                    onSuccess = {
                        publishPlaylistChange()
                        Resource.Success(Unit)
                    },
                    onFailure = { error -> Resource.Error(playlistMutationError(playlistId, error)) }
                )
            }
        }

    /** @see PlaylistRepository.replaceTracks */
    override suspend fun replaceTracks(playlistId: String, trackUris: List<String>): Resource<Unit> =
        withContext(ioDispatcher) {
            mutationMutex.withLock {
                runCatching {
                    if (playlistId == FAVORITES_PLAYLIST_ID) {
                        synchronizeFavoritesFile(likedSongDao.getLikedSongIds())
                    }
                    replacePlaylistContents(requirePlaylist(playlistId), trackUris)
                }.fold(
                    onSuccess = {
                        publishPlaylistChange()
                        Resource.Success(Unit)
                    },
                    onFailure = { error -> Resource.Error(playlistMutationError(playlistId, error)) }
                )
            }
        }

    /** @see PlaylistRepository.deletePlaylist */
    override suspend fun deletePlaylist(playlistId: String): Resource<Unit> = withContext(ioDispatcher) {
        mutationMutex.withLock {
            runCatching {
                check(playlistId != FAVORITES_PLAYLIST_ID) {
                    "The favorites playlist can't be deleted."
                }
                val playlist = requirePlaylist(playlistId)
                if (playlist.kind == PlaylistKind.IMPORTED) {
                    check(DocumentsContract.deleteDocument(contentResolver, Uri.parse(playlist.id))) {
                        "Unable to delete the playlist file."
                    }
                    importedPlaylistDao.deleteByDocumentUri(playlist.id)
                } else {
                    check(playlistDirectory().resolve(playlist.id).delete()) {
                        "Unable to delete the playlist file."
                    }
                }
            }.fold(
                onSuccess = {
                    publishPlaylistChange()
                    Resource.Success(Unit)
                },
                onFailure = { error -> Resource.Error(playlistMutationError(playlistId, error)) }
            )
        }
    }

    /** @see LikedSongsRepository.observeLikedSongIds */
    override fun observeLikedSongIds(): Flow<Set<Long>> = likedSongDao.observeLikedSongIds()
        .map(List<Long>::toSet)
        .catch { emit(emptySet()) }
        .flowOn(ioDispatcher)

    /** @see LikedSongsRepository.getLikedTrackIds */
    override suspend fun getLikedTrackIds(): Resource<Set<Long>> = withContext(ioDispatcher) {
        runCatching { likedSongDao.getLikedSongIds().toSet() }.fold(
            onSuccess = { ids -> Resource.Success(ids) },
            onFailure = { error -> Resource.Error(databaseError(error)) }
        )
    }

    /** @see LikedSongsRepository.toggleLike */
    override suspend fun toggleLike(trackId: Long): Resource<Unit> = withContext(ioDispatcher) {
        mutationMutex.withLock {
            runCatching {
                val track = libraryIndexDao.getTracksByIds(listOf(trackId)).firstOrNull()
                    ?: error("The selected track is no longer present in the local library.")
                val currentRows = likedSongDao.getLikedSongs()
                synchronizeFavoritesFile(currentRows.map(LikedSongEntity::trackId))
                val isLiked = currentRows.any { row -> row.trackId == trackId }
                val desiredRows = if (isLiked) {
                    currentRows.filterNot { row -> row.trackId == trackId }
                } else {
                    currentRows + LikedSongEntity(trackId = trackId, likedAt = nextLikedAt(currentRows))
                }
                val currentUris = currentFavoritesUris()
                val desiredUris = if (isLiked) {
                    currentUris.filterNot { uri -> uri == track.contentUri }
                } else {
                    currentUris.filterNot { uri -> uri == track.contentUri } + track.contentUri
                }
                persistFavorites(desiredRows, desiredUris)
            }.fold(
                onSuccess = {
                    publishPlaylistChange()
                    Resource.Success(Unit)
                },
                onFailure = { error -> Resource.Error(storageOrDatabaseError(error)) }
            )
        }
    }

    /** @see LikedSongsRepository.setTracksLiked */
    override suspend fun setTracksLiked(
        trackIds: List<Long>,
        isLiked: Boolean
    ): Resource<Unit> = withContext(ioDispatcher) {
        mutationMutex.withLock {
            runCatching {
                val requestedIds = trackIds.distinct()
                require(requestedIds.isNotEmpty()) { "Select at least one track to update." }

                val tracksById = libraryIndexDao.getTracksByIds(requestedIds)
                    .associateBy { track -> track.id }
                check(requestedIds.all(tracksById::containsKey)) {
                    "One or more selected tracks are no longer present in the local library."
                }

                val currentRows = likedSongDao.getLikedSongs()
                synchronizeFavoritesFile(currentRows.map(LikedSongEntity::trackId))
                val requestedIdSet = requestedIds.toSet()
                val currentUris = currentFavoritesUris()

                if (isLiked) {
                    val existingIds = currentRows.mapTo(mutableSetOf(), LikedSongEntity::trackId)
                    val missingIds = requestedIds.filterNot(existingIds::contains)
                    if (missingIds.isNotEmpty()) {
                        val firstLikedAt = nextLikedAt(currentRows)
                        val desiredRows = currentRows + missingIds.mapIndexed { index, id ->
                            LikedSongEntity(trackId = id, likedAt = firstLikedAt + index)
                        }
                        val missingUris = missingIds.map { id -> tracksById.getValue(id).contentUri }
                        val desiredUris = currentUris + missingUris.filterNot(currentUris::contains)
                        persistFavorites(desiredRows, desiredUris)
                    }
                } else {
                    val desiredRows = currentRows.filterNot { row -> row.trackId in requestedIdSet }
                    if (desiredRows.size != currentRows.size) {
                        val requestedUris = requestedIds
                            .mapTo(mutableSetOf()) { id -> tracksById.getValue(id).contentUri }
                        val desiredUris = currentUris.filterNot(requestedUris::contains)
                        persistFavorites(desiredRows, desiredUris)
                    }
                }
            }.fold(
                onSuccess = {
                    publishPlaylistChange()
                    Resource.Success(Unit)
                },
                onFailure = { error -> Resource.Error(storageOrDatabaseError(error)) }
            )
        }
    }

    /** Appends unique liked tracks and persists the resulting playlist/Room snapshot together. */
    private suspend fun appendFavorites(tracks: List<Track>) {
        val currentRows = likedSongDao.getLikedSongs()
        val existingIds = currentRows.mapTo(mutableSetOf(), LikedSongEntity::trackId)
        val uniqueTracks = tracks.distinctBy(Track::id).filterNot { track -> track.id in existingIds }
        if (uniqueTracks.isEmpty()) return

        val firstLikedAt = nextLikedAt(currentRows)
        val desiredRows = currentRows + uniqueTracks.mapIndexed { index, track ->
            LikedSongEntity(trackId = track.id, likedAt = firstLikedAt + index)
        }
        val currentUris = currentFavoritesUris()
        val desiredUris = currentUris + uniqueTracks.map(Track::uri).filterNot(currentUris::contains)
        persistFavorites(desiredRows, desiredUris)
    }

    /** Routes a favorites membership replacement, imported SAF write, or app-private file write. */
    private suspend fun replacePlaylistContents(playlist: Playlist, trackUris: List<String>) {
        when (playlist.kind) {
            PlaylistKind.FAVORITES -> {
                val tracksByUri = if (trackUris.isEmpty()) {
                    emptyMap()
                } else {
                    libraryIndexDao.getTracks()
                        .filter { entity -> entity.contentUri in trackUris }
                        .associateBy { entity -> entity.contentUri }
                }
                check(trackUris.all(tracksByUri::containsKey)) {
                    "Unavailable tracks must be removed before the favorites playlist can be saved."
                }
                val distinctUris = trackUris.distinct()
                val baseLikedAt = System.currentTimeMillis()
                val desiredRows = distinctUris.mapIndexed { index, uri ->
                    LikedSongEntity(
                        trackId = tracksByUri.getValue(uri).id,
                        likedAt = baseLikedAt + index
                    )
                }
                persistFavorites(desiredRows, distinctUris)
            }

            PlaylistKind.IMPORTED -> writeImportedPlaylist(playlist.id, playlist.name, trackUris)

            PlaylistKind.STANDARD ->
                writePlaylist(playlistDirectory().resolve(playlist.id), playlist.name, trackUris)
        }
    }

    /** Writes favorites first, rolls the file back if the Room transaction is rejected. */
    private suspend fun persistFavorites(rows: List<LikedSongEntity>, trackUris: List<String>) {
        val file = favoritesFile()
        val previous = file.takeIf(File::isFile)?.readText(Charsets.UTF_8)
        writePlaylist(file, favoritesName(), trackUris)
        try {
            likedSongDao.replaceLikedSongs(rows)
        } catch (error: Throwable) {
            runCatching {
                if (previous == null) {
                    file.delete()
                } else {
                    writeRawFile(file, previous)
                }
            }
            throw error
        }
    }

    /** Materializes or repairs the reserved M3U from the authoritative liked ID ordering. */
    private suspend fun synchronizeFavoritesFile(likedIds: List<Long>) {
        val tracksById = if (likedIds.isEmpty()) {
            emptyMap()
        } else {
            libraryIndexDao.getTracksByIds(likedIds).associateBy { entity -> entity.id }
        }
        val desiredUris = likedIds.mapNotNull { id -> tracksById[id]?.contentUri }
        val existing = readPlaylist(favoritesFile())
        if (existing?.trackUris != desiredUris || existing.name != favoritesName()) {
            writePlaylist(favoritesFile(), favoritesName(), desiredUris)
        }
    }

    /** Reads all valid M3U files so process recreation restores the disk collection. */
    private fun loadPlaylists(): List<Playlist> = playlistDirectory()
        .listFiles { file -> file.isFile && file.extension.equals(M3U_EXTENSION, ignoreCase = true) }
        .orEmpty()
        .mapNotNull(::readPlaylist)
        .sortedWith(PLAYLIST_ORDER)

    /** Maps one discovered `.m3u`/`.m3u8` row to its domain representation. */
    private fun toImportedPlaylist(entity: ImportedPlaylistEntity): Playlist = Playlist(
        id = entity.documentUri,
        name = entity.name,
        trackUris = entity.trackUris,
        kind = PlaylistKind.IMPORTED,
    )

    /**
     * Resolves one playlist or rejects a stale navigation/picker identifier.
     *
     * Checks the app-private collection first, then the discovered `.m3u` collection —
     * an imported playlist's stable ID is its source document URI, not a filename.
     */
    private suspend fun requirePlaylist(playlistId: String): Playlist = loadPlaylists()
        .firstOrNull { playlist -> playlist.id == playlistId }
        ?: importedPlaylistDao.getByDocumentUri(playlistId)?.let(::toImportedPlaylist)
        ?: error("The selected playlist no longer exists.")

    /** Reads the favorites URI order without exposing the file outside Data. */
    private fun currentFavoritesUris(): List<String> = readPlaylist(favoritesFile())?.trackUris.orEmpty()

    /** Publishes a monotonically increasing invalidation after a successful mutation. */
    private fun publishPlaylistChange() {
        playlistRevision.value = playlistRevision.value + 1L
    }

    /** Atomically rewrites one app-owned M3U with the supplied membership and URI order. */
    private fun writePlaylist(file: File, name: String, trackUris: List<String>) {
        writeRawFile(
            file = file,
            contents = buildString {
                append("#EXTM3U\n#PLAYLIST:")
                append(name)
                append('\n')
                trackUris.forEach { uri -> append(uri).append('\n') }
            }
        )
    }

    /**
     * Rewrites a discovered `.m3u` document in place via its SAF grant and refreshes its
     * cached row so [observePlaylists] reflects the edit before the next library scan.
     *
     * Each track is written as its real filesystem path when one is known (true for
     * ordinary MediaStore tracks), keeping the file readable by other players; DSD tracks
     * and any track no longer in the index fall back to their content URI.
     */
    private suspend fun writeImportedPlaylist(documentUri: String, name: String, trackUris: List<String>) {
        val tracksByUri = if (trackUris.isEmpty()) {
            emptyMap()
        } else {
            libraryIndexDao.getTracks()
                .filter { entity -> entity.contentUri in trackUris }
                .associateBy { entity -> entity.contentUri }
        }
        val contents = buildString {
            append("#EXTM3U\n#PLAYLIST:")
            append(name)
            append('\n')
            trackUris.forEach { uri ->
                val filePath = tracksByUri[uri]?.filePath
                append(if (filePath != null && filePath.startsWith("/")) filePath else uri)
                append('\n')
            }
        }
        val uri = Uri.parse(documentUri)
        val stream = contentResolver.openOutputStream(uri, "wt")
        checkNotNull(stream) { "Unable to open the playlist file for writing." }
        stream.use { it.write(contents.toByteArray(Charsets.UTF_8)) }

        importedPlaylistDao.upsert(
            ImportedPlaylistEntity(
                documentUri = documentUri,
                name = name,
                trackUris = trackUris,
                lastModifiedMs = queryLastModified(uri) ?: System.currentTimeMillis(),
            )
        )
    }

    /** Reads a SAF document's last-modified time, matching what a future scan would see. */
    private fun queryLastModified(uri: Uri): Long? = runCatching {
        contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
            null,
            null,
            null
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
    }.getOrNull()

    /** Uses a same-directory temporary file so readers never observe a partial playlist. */
    private fun writeRawFile(file: File, contents: String) {
        file.parentFile?.mkdirs()
        val temporaryFile = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.tmp")
        try {
            temporaryFile.writeText(contents, Charsets.UTF_8)
            try {
                Files.move(
                    temporaryFile.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporaryFile.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        } finally {
            temporaryFile.delete()
        }
    }

    /** Parses only the M3U fields Audiophile writes; unknown extended tags stay harmless. */
    private fun readPlaylist(file: File): Playlist? = runCatching {
        val lines = file.readLines(Charsets.UTF_8)
        val isFavorites = file.name == FAVORITES_PLAYLIST_ID
        val name = if (isFavorites) {
            favoritesName()
        } else {
            lines.firstOrNull { it.startsWith(PLAYLIST_NAME_PREFIX) }
                ?.removePrefix(PLAYLIST_NAME_PREFIX)
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?: file.nameWithoutExtension
        }
        val trackUris = lines.filter { line -> line.isNotBlank() && !line.startsWith("#") }
        Playlist(
            id = file.name,
            name = name,
            trackUris = trackUris,
            kind = if (isFavorites) PlaylistKind.FAVORITES else PlaylistKind.STANDARD
        )
    }.getOrNull()

    /** Creates the app-private M3U directory on demand. */
    private fun playlistDirectory(): File = File(context.filesDir, PLAYLIST_DIRECTORY).apply(File::mkdirs)

    /** Resolves the localized system-playlist title stored in the extended M3U header. */
    private fun favoritesName(): String = context.getString(R.string.library_favorites_title)

    /** Resolves the reserved playlist file shared by likes and playlist detail. */
    private fun favoritesFile(): File = playlistDirectory().resolve(FAVORITES_PLAYLIST_ID)

    /** Generates a timestamp that keeps new likes after every currently persisted row. */
    private fun nextLikedAt(rows: List<LikedSongEntity>): Long {
        val now = System.currentTimeMillis()
        return maxOf(now, (rows.maxOfOrNull(LikedSongEntity::likedAt) ?: now - 1L) + 1L)
    }

    /** Converts filesystem/coordinated persistence failures to the public storage error model. */
    private fun storageError(error: Throwable): ResourceError.StorageError = ResourceError.StorageError(
        error.message ?: "Unable to update the local playlist."
    )

    /** Converts a Room read failure to the public database error model. */
    private fun databaseError(error: Throwable): ResourceError.DatabaseError = ResourceError.DatabaseError(
        error.message ?: "Unable to read liked songs."
    )

    /** Classifies coordinated favorites failures without leaking storage or Room exceptions. */
    private fun storageOrDatabaseError(error: Throwable): ResourceError = when (error) {
        is java.io.IOException -> storageError(error)
        is IllegalArgumentException, is IllegalStateException -> ResourceError.LogicError(error.message)
        else -> ResourceError.DatabaseError(error.message ?: "Unable to update liked songs.")
    }

    /** Selects validation, storage, or database semantics for a playlist mutation failure. */
    private fun playlistMutationError(playlistId: String, error: Throwable): ResourceError = when (error) {
        is IllegalArgumentException, is IllegalStateException -> ResourceError.LogicError(error.message)
        else -> if (playlistId == FAVORITES_PLAYLIST_ID) storageOrDatabaseError(error) else storageError(error)
    }

    private companion object {
        const val PLAYLIST_DIRECTORY = "playlists"
        const val M3U_EXTENSION = "m3u"
        const val PLAYLIST_NAME_PREFIX = "#PLAYLIST:"
        const val FAVORITES_PLAYLIST_ID = "favorites.m3u"

        /** Favorites first, then every other playlist (standard or imported) alphabetically. */
        val PLAYLIST_ORDER: Comparator<Playlist> =
            compareBy<Playlist> { it.kind != PlaylistKind.FAVORITES }.thenBy { it.name.lowercase() }
    }
}
