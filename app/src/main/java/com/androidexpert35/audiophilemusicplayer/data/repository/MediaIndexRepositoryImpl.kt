package com.androidexpert35.audiophilemusicplayer.data.repository

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.androidexpert35.audiophilemusicplayer.data.local.dao.ImportedPlaylistDao
import com.androidexpert35.audiophilemusicplayer.data.local.dao.LibraryIndexDao
import com.androidexpert35.audiophilemusicplayer.data.local.entity.LibraryIndexStateEntity
import com.androidexpert35.audiophilemusicplayer.data.local.entity.TrackEntity
import com.androidexpert35.audiophilemusicplayer.data.mapper.toAlbumEntities
import com.androidexpert35.audiophilemusicplayer.data.mapper.toArtistEntities
import com.androidexpert35.audiophilemusicplayer.data.mapper.toTrackEntity
import com.androidexpert35.audiophilemusicplayer.data.repository.MediaIndexRepositoryImpl.Companion.SCAN_PHASE_WEIGHT
import com.androidexpert35.audiophilemusicplayer.data.scanner.DsdFileScanner
import com.androidexpert35.audiophilemusicplayer.data.scanner.M3uFileScanner
import com.androidexpert35.audiophilemusicplayer.data.scanner.MediaStoreScanner
import com.androidexpert35.audiophilemusicplayer.di.IoDispatcher
import com.androidexpert35.audiophilemusicplayer.domain.model.indexing.MediaIndexingProgress
import com.androidexpert35.audiophilemusicplayer.domain.repository.MediaIndexRepository
import com.tony.coreui.domain.resource.Resource
import com.tony.coreui.domain.resource.ResourceError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository implementation that scans the user's music folders, supplements the result with
 * a DSD document scan, and writes a cached Room index.
 *
 * The scan is scoped to the folders held by [MusicFolderRegistry] — never the whole device.
 * That scope is what keeps unrelated audio (messenger voice notes above all) out of the
 * catalogue, and its persisted read grants are the only way DSD containers can be read at all.
 * With no granted folder the index is left untouched and a [ResourceError.StorageError] is
 * emitted, so onboarding can ask the user to pick a folder instead of silently wiping the
 * catalogue.
 *
 * The scanner reads MediaStore once to discover the audio files inside those folders, then this
 * repository derives the normalized track/album/artist tables and persists them transactionally
 * for fast later reads. DSD files (`.dsf`, `.dff`) are discovered by [DsdFileScanner] and merged
 * into the same pipeline because Android's system media scanner does not index these formats.
 * `.m3u`/`.m3u8` playlist files are discovered the same way by [M3uFileScanner], which resolves
 * each entry against the combined MediaStore+DSD result and is persisted separately via
 * [ImportedPlaylistDao] rather than into the track/album/artist tables.
 *
 * @property scanner MediaStore query executor for raw audio metadata.
 * @property dsdFileScanner Document-tree scanner for DSD files invisible to MediaStore.
 * @property m3uFileScanner Document-tree scanner for `.m3u`/`.m3u8` playlists invisible to MediaStore.
 * @property musicFolderRegistry Source of truth for the folders the scan may read.
 * @property libraryIndexDao DAO used to replace the cached indexed library atomically.
 * @property importedPlaylistDao DAO used to replace the cached discovered-playlist table.
 * @property contentResolver System content resolver used to observe MediaStore changes.
 * @property ioDispatcher Dispatcher reserved for blocking scan and indexing work.
 */
@Singleton
class MediaIndexRepositoryImpl @Inject constructor(
    private val scanner: MediaStoreScanner,
    private val dsdFileScanner: DsdFileScanner,
    private val m3uFileScanner: M3uFileScanner,
    private val musicFolderRegistry: MusicFolderRegistry,
    private val libraryIndexDao: LibraryIndexDao,
    private val importedPlaylistDao: ImportedPlaylistDao,
    private val contentResolver: ContentResolver,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : MediaIndexRepository {

    /**
     * Runs the scan on [ioDispatcher] and pushes progress via [kotlinx.coroutines.channels.ProducerScope.trySend]
     * so it can report intermediate steps from [MediaStoreScanner]'s per-file callback, which
     * `flow {}` cannot safely do across a dispatcher hop without violating context preservation.
     *
     * Progress is a weighted blend of two phases: the MediaStore scan (including the ID3v2.2
     * metadata fallback pass) is the dominant real I/O cost and is weighted [SCAN_PHASE_WEIGHT]
     * of the total bar; the remaining phase (DSD scan, in-memory entity mapping, and the Room
     * write) is comparatively fast in-memory work and shares what's left.
     */
    override fun scanAndIndexMedia(): Flow<Resource<MediaIndexingProgress>> = callbackFlow {
        trySend(
            Resource.Success(
                MediaIndexingProgress(
                    progress = 0f,
                    currentFile = "",
                    indexedFiles = 0,
                    totalFiles = 0
                )
            )
        )

        val job = launch(ioDispatcher) {
            try {
                val folders = musicFolderRegistry.getScopes()

                // An empty scope has two very different causes and only one of them means
                // the catalogue should disappear.
                //
                // The user removing their last folder must empty the library — leaving the
                // old tracks visible would keep playing content from a location the user
                // just revoked. That case falls through and indexes an empty result set,
                // which clears the tables.
                //
                // Folders still on record that simply cannot be resolved right now (card
                // unmounted, grant not yet restored after a reboot) are transient. Wiping
                // there would destroy a good catalogue over a temporary condition, so the
                // scan aborts and leaves the index untouched.
                if (folders.isEmpty() && musicFolderRegistry.hasStoredFolders()) {
                    trySend(
                        Resource.Error(
                            ResourceError.StorageError(
                                "Your music folders are not reachable right now. Reconnect the storage holding them, or add a folder again."
                            )
                        )
                    )
                    return@launch
                }

                val mediaStoreFiles = scanner.scanAudioFilesForIndexing(folders) { processed, total, filePath ->
                    if (total > 0) {
                        trySend(
                            Resource.Success(
                                MediaIndexingProgress(
                                    progress = (processed / total.toFloat()) * SCAN_PHASE_WEIGHT,
                                    currentFile = filePath,
                                    indexedFiles = processed,
                                    totalFiles = total
                                )
                            )
                        )
                    }
                }
                // DSD files (.dsf / .dff) are not indexed by Android's system scanner and are
                // not covered by READ_MEDIA_AUDIO, so DsdFileScanner walks the same granted
                // folders through their document-tree grants as a supplementary pass; its
                // results are merged here before indexing into Room.
                val dsdFiles = dsdFileScanner.scanDsdFiles(folders)
                val scannedFiles = mediaStoreFiles + dsdFiles
                val totalFiles = scannedFiles.size

                // `.m3u`/`.m3u8` playlists are likewise invisible to MediaStore. They're
                // resolved against scannedFiles here — before the Room write below — so the
                // scanner never needs a separate query against the indexed track table.
                val importedPlaylists = m3uFileScanner.scanPlaylists(folders, scannedFiles)
                val trackEntities = ArrayList<TrackEntity>(scannedFiles.size)

                for ((index, file) in scannedFiles.withIndex()) {
                    trackEntities += file.toTrackEntity()

                    // Emit progress for every file except the last — the final 100% emission is
                    // sent only after the Room transaction commits, guaranteeing that collectors
                    // reading isLibraryIndexed() immediately after this emission see the committed
                    // data rather than a partially-written index.
                    val isLastFile = index == scannedFiles.lastIndex
                    if (!isLastFile) {
                        val mappingProgress = (index + 1) / totalFiles.toFloat()
                        trySend(
                            Resource.Success(
                                MediaIndexingProgress(
                                    progress = SCAN_PHASE_WEIGHT +
                                        mappingProgress * (1f - SCAN_PHASE_WEIGHT),
                                    currentFile = file.filePath,
                                    indexedFiles = index + 1,
                                    totalFiles = totalFiles
                                )
                            )
                        )
                    }
                }

                val albumEntities = scannedFiles.toAlbumEntities()
                val artistEntities = scannedFiles.toArtistEntities()
                val state = LibraryIndexStateEntity(
                    isCompleted = true,
                    indexedTrackCount = trackEntities.size,
                    lastIndexedAtEpochMs = System.currentTimeMillis(),
                    // Stamp the scope that produced this catalogue so a later folder change
                    // is detectable without having to diff the tracks themselves.
                    folderSignature = musicFolderRegistry.folderSignature()
                )

                // Atomic Room transaction — must complete before the 100% progress is emitted.
                libraryIndexDao.replaceIndexedLibrary(
                    tracks = trackEntities,
                    albums = albumEntities,
                    artists = artistEntities,
                    state = state
                )
                importedPlaylistDao.replaceAll(importedPlaylists)

                // Final emission after the transaction commits, so the UI can safely
                // call isLibraryIndexed() or open the library screen.
                trySend(
                    Resource.Success(
                        MediaIndexingProgress(
                            progress = 1f,
                            currentFile = scannedFiles.lastOrNull()?.filePath ?: "",
                            indexedFiles = trackEntities.size,
                            totalFiles = totalFiles
                        )
                    )
                )
            } catch (throwable: Throwable) {
                trySend(
                    Resource.Error(
                        ResourceError.StorageError(
                            throwable.message ?: "Failed to index local audio library"
                        )
                    )
                )
            } finally {
                close()
            }
        }

        awaitClose { job.cancel() }
    }

    /**
     * A cached index counts as usable only when it was built from the folders granted *now*,
     * and only when there is a real folder behind it.
     *
     * Comparing the stored signature is what makes a folder removed in Settings actually
     * disappear: the catalogue is declared stale on the next launch even if nothing was
     * running to observe the change when it happened.
     *
     * An **empty** signature never counts as a match, even against an equally empty current
     * scope. Two different situations both produce an empty signature, and serving a
     * catalogue in either would be wrong:
     * - a library indexed before folder scoping existed, i.e. the whole-device scan full of
     *   messenger voice notes — an upgrading user must be sent through the folder step
     *   rather than dropped straight into that stale catalogue;
     * - a user who removed their last folder, whose library is legitimately empty and who
     *   needs to be asked for a folder before anything can be shown.
     *
     * In both cases the caller should route to onboarding, which is exactly what returning
     * `false` here does.
     */
    override suspend fun isLibraryIndexed(): Boolean = runCatching {
        val state = libraryIndexDao.getLibraryIndexState() ?: return@runCatching false
        val currentSignature = musicFolderRegistry.folderSignature()
        state.isCompleted &&
            currentSignature.isNotEmpty() &&
            state.folderSignature == currentSignature
    }.getOrDefault(false)

    /**
     * Signals every reason the cached library could now be stale: audio files changing on the
     * device, and the user changing which folders the library is built from.
     *
     * Folder edits are merged in because adding or removing a music folder changes the scan
     * scope exactly as much as copying files onto the device does — without this, a folder
     * added in Settings would not show up until the next manual refresh. The registry's
     * initial emission is dropped so only actual edits trigger work; the immediate [Unit]
     * still comes from the content observer below.
     */
    override fun observeMediaStoreChanges(): Flow<Unit> = merge(
        observeAudioContentChanges(),
        musicFolderRegistry.observeScopes().drop(1).map { },
    )

    /**
     * Wraps [ContentResolver.registerContentObserver] in a [callbackFlow] so that any addition
     * or removal of audio files in the device MediaStore is pushed as a Flow emission.
     *
     * An initial [Unit] is sent immediately so the first collector receives a value before any
     * change event arrives. [awaitClose] unregisters the observer automatically when the
     * collecting scope is cancelled — no manual lifecycle wiring is required.
     */
    private fun observeAudioContentChanges(): Flow<Unit> = callbackFlow {
        // Emit immediately so the caller has an initial value without waiting for a change event.
        trySend(Unit)

        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }

        contentResolver.registerContentObserver(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            /* notifyForDescendants = */ true,
            observer
        )

        // Unregisters the observer when the collecting coroutine scope is cancelled.
        awaitClose {
            contentResolver.unregisterContentObserver(observer)
        }
    }

    private companion object {
        /**
         * Share of the onboarding progress bar attributed to the MediaStore scan phase
         * (cursor read + ID3v2.2 metadata fallback). That phase does real per-file disk I/O
         * and dominates wall-clock time for real libraries; the remaining phase (DSD scan,
         * in-memory entity mapping, Room write) is comparatively instant.
         */
        const val SCAN_PHASE_WEIGHT = 0.7f
    }
}

