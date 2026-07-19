package com.androidexpert35.audiophilemusicplayer.data.repository

import android.content.Context
import com.androidexpert35.audiophilemusicplayer.di.IoDispatcher
import com.androidexpert35.audiophilemusicplayer.domain.model.library.Playlist
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track
import com.androidexpert35.audiophilemusicplayer.domain.repository.PlaylistRepository
import com.tony.coreui.domain.resource.Resource
import com.tony.coreui.domain.resource.ResourceError
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * File-backed implementation of [PlaylistRepository] using UTF-8 extended M3U files.
 *
 * The files deliberately live in the application's private files directory: Android 13+
 * scoped storage makes arbitrary shared-storage writes unreliable, while MediaStore content
 * URIs embedded in each M3U remain stable for playback and no broad write permission is needed.
 *
 * @property context Application context used to resolve the private playlist directory.
 * @property ioDispatcher Dispatcher for filesystem work.
 */
@Singleton
class PlaylistRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : PlaylistRepository {

    private val playlists = MutableStateFlow<List<Playlist>>(emptyList())

    /** @see PlaylistRepository.observePlaylists */
    override fun observePlaylists(): Flow<List<Playlist>> = flow {
        // Loading happens in the collector's injected IO context rather than during Hilt
        // construction, keeping ViewModel creation free of filesystem work on the main thread.
        playlists.value = loadPlaylists()
        emitAll(playlists.asStateFlow())
    }.flowOn(ioDispatcher)

    /** @see PlaylistRepository.createPlaylist */
    override suspend fun createPlaylist(name: String): Resource<Playlist> = withContext(ioDispatcher) {
        val normalizedName = name.trim()
        val currentPlaylists = loadPlaylists()
        when {
            normalizedName.isBlank() -> Resource.Error(
                ResourceError.LogicError("A playlist name is required.")
            )

            currentPlaylists.any { it.name.equals(normalizedName, ignoreCase = true) } -> Resource.Error(
                ResourceError.LogicError("A playlist with this name already exists.")
            )

            else -> runCatching {
                val id = "${UUID.randomUUID()}.m3u"
                playlistDirectory().resolve(id).apply {
                    writeText("#EXTM3U\n#PLAYLIST:$normalizedName\n", Charsets.UTF_8)
                }
                Playlist(id = id, name = normalizedName, trackUris = emptyList())
            }.fold(
                onSuccess = { playlist ->
                    refreshPlaylists()
                    Resource.Success(playlist)
                },
                onFailure = { error -> Resource.Error(storageError(error)) }
            )
        }
    }

    /** @see PlaylistRepository.addTrack */
    override suspend fun addTrack(playlistId: String, track: Track): Resource<Unit> = withContext(ioDispatcher) {
        runCatching {
            val playlist = loadPlaylists().firstOrNull { it.id == playlistId }
                ?: error("The selected playlist no longer exists.")
            val file = playlistDirectory().resolve(playlist.id)
            check(file.isFile) { "The selected playlist file no longer exists." }

            // Keep duplicate additions intentional: a playlist can legitimately repeat a song.
            file.appendText(
                "#EXTINF:${track.durationMs / 1_000},${track.artistName} - ${track.title}\n${track.uri}\n",
                Charsets.UTF_8
            )
        }.fold(
            onSuccess = {
                refreshPlaylists()
                Resource.Success(Unit)
            },
            onFailure = { error -> Resource.Error(storageError(error)) }
        )
    }

    /** @see PlaylistRepository.addTracks */
    override suspend fun addTracks(
        playlistId: String,
        tracks: List<Track>
    ): Resource<Unit> = withContext(ioDispatcher) {
        runCatching {
            require(tracks.isNotEmpty()) { "Select at least one track to add." }
            val playlist = loadPlaylists().firstOrNull { it.id == playlistId }
                ?: error("The selected playlist no longer exists.")
            val file = playlistDirectory().resolve(playlist.id)
            check(file.isFile) { "The selected playlist file no longer exists." }

            file.appendText(
                buildString {
                    tracks.forEach { track ->
                        append("#EXTINF:")
                        append(track.durationMs / 1_000)
                        append(',')
                        append(track.artistName)
                        append(" - ")
                        append(track.title)
                        append('\n')
                        append(track.uri)
                        append('\n')
                    }
                },
                Charsets.UTF_8
            )
        }.fold(
            onSuccess = {
                refreshPlaylists()
                Resource.Success(Unit)
            },
            onFailure = { error -> Resource.Error(storageError(error)) }
        )
    }


    /** @see PlaylistRepository.reorderTracks */
    override suspend fun reorderTracks(playlistId: String, trackUris: List<String>): Resource<Unit> =
        withContext(ioDispatcher) {
            runCatching {
                val playlist = loadPlaylists().firstOrNull { it.id == playlistId }
                    ?: error("The selected playlist no longer exists.")
                check(playlist.trackUris.groupingBy { it }.eachCount() ==
                    trackUris.groupingBy { it }.eachCount()) {
                    "The playlist entries changed before their order could be saved."
                }

                val file = playlistDirectory().resolve(playlist.id)
                check(file.isFile) { "The selected playlist file no longer exists." }

                // Rewrite the compact app-owned M3U representation in one operation. URI
                // order is the playlist contract; EXTINF metadata is optional and can be
                // regenerated when a song is added again.
                file.writeText(
                    buildString {
                        append("#EXTM3U\n#PLAYLIST:")
                        append(playlist.name)
                        append('\n')
                        trackUris.forEach { uri -> append(uri).append('\n') }
                    },
                    Charsets.UTF_8
                )
            }.fold(
                onSuccess = {
                    refreshPlaylists()
                    Resource.Success(Unit)
                },
                onFailure = { error -> Resource.Error(storageError(error)) }
            )
        }

    /** @see PlaylistRepository.replaceTracks */
    override suspend fun replaceTracks(playlistId: String, trackUris: List<String>): Resource<Unit> =
        withContext(ioDispatcher) {
            runCatching {
                val playlist = loadPlaylists().firstOrNull { it.id == playlistId }
                    ?: error("The selected playlist no longer exists.")
                val file = playlistDirectory().resolve(playlist.id)
                check(file.isFile) { "The selected playlist file no longer exists." }
                writePlaylist(file, playlist.name, trackUris)
            }.fold(
                onSuccess = {
                    refreshPlaylists()
                    Resource.Success(Unit)
                },
                onFailure = { error -> Resource.Error(storageError(error)) }
            )
        }

    /** Reads all valid M3U files so process recreation restores the actual on-disk collection. */
    private fun loadPlaylists(): List<Playlist> = runCatching {
        playlistDirectory()
            .listFiles { file -> file.isFile && file.extension.equals(M3U_EXTENSION, ignoreCase = true) }
            .orEmpty()
            .mapNotNull(::readPlaylist)
            .sortedBy { it.name.lowercase() }
    }.getOrDefault(emptyList())

    /** Publishes the new disk snapshot after a successful local write. */
    private fun refreshPlaylists() {
        playlists.value = loadPlaylists()
    }

    /** Rewrites one app-owned M3U with the supplied membership and stable URI order. */
    private fun writePlaylist(file: File, name: String, trackUris: List<String>) {
        file.writeText(
            buildString {
                append("#EXTM3U\n#PLAYLIST:")
                append(name)
                append('\n')
                trackUris.forEach { uri -> append(uri).append('\n') }
            },
            Charsets.UTF_8
        )
    }

    /** Parses only the M3U fields Audiophile writes; unknown extended tags stay harmless. */
    private fun readPlaylist(file: File): Playlist? = runCatching {
        val lines = file.readLines(Charsets.UTF_8)
        val name = lines.firstOrNull { it.startsWith(PLAYLIST_NAME_PREFIX) }
            ?.removePrefix(PLAYLIST_NAME_PREFIX)
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: file.nameWithoutExtension
        val trackUris = lines.filter { line -> line.isNotBlank() && !line.startsWith("#") }
        Playlist(id = file.name, name = name, trackUris = trackUris)
    }.getOrNull()

    /** Creates the app-private M3U directory on demand. */
    private fun playlistDirectory(): File = File(context.filesDir, PLAYLIST_DIRECTORY).apply { mkdirs() }

    /** Converts any file failure to the domain error model at the data boundary. */
    private fun storageError(error: Throwable): ResourceError.StorageError = ResourceError.StorageError(
        error.message ?: "Unable to update the local playlist."
    )

    private companion object {
        const val PLAYLIST_DIRECTORY = "playlists"
        const val M3U_EXTENSION = "m3u"
        const val PLAYLIST_NAME_PREFIX = "#PLAYLIST:"
    }
}
