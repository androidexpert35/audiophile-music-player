package com.androidexpert35.audiophilemusicplayer.data.repository

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.androidexpert35.audiophilemusicplayer.data.local.dao.LibraryIndexDao
import com.androidexpert35.audiophilemusicplayer.data.local.entity.LibraryIndexStateEntity
import com.androidexpert35.audiophilemusicplayer.data.local.entity.TrackEntity
import com.androidexpert35.audiophilemusicplayer.data.mapper.toAlbumEntities
import com.androidexpert35.audiophilemusicplayer.data.mapper.toArtistEntities
import com.androidexpert35.audiophilemusicplayer.data.mapper.toTrackEntity
import com.androidexpert35.audiophilemusicplayer.data.repository.MediaIndexRepositoryImpl.Companion.SCAN_PHASE_WEIGHT
import com.androidexpert35.audiophilemusicplayer.data.scanner.DsdFileScanner
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
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository implementation that performs the initial MediaStore scan, supplements it with
 * a DSD filesystem scan, and writes a cached Room index.
 *
 * The scanner reads MediaStore once to discover all audio files, then this repository derives
 * the normalized track/album/artist tables and persists them transactionally for fast later reads.
 * DSD files (`.dsf`, `.dff`) are discovered by [DsdFileScanner] and merged into the same
 * pipeline because Android's system media scanner does not index these formats in MediaStore.
 *
 * @property scanner MediaStore query executor for raw audio metadata.
 * @property dsdFileScanner Filesystem scanner for DSD files invisible to MediaStore.
 * @property libraryIndexDao DAO used to replace the cached indexed library atomically.
 * @property contentResolver System content resolver used to observe MediaStore changes.
 * @property ioDispatcher Dispatcher reserved for blocking scan and indexing work.
 */
@Singleton
class MediaIndexRepositoryImpl @Inject constructor(
    private val scanner: MediaStoreScanner,
    private val dsdFileScanner: DsdFileScanner,
    private val libraryIndexDao: LibraryIndexDao,
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
                val mediaStoreFiles = scanner.scanAudioFilesForIndexing { processed, total, filePath ->
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
                // DSD files (.dsf / .dff) are not indexed by Android's system scanner, so
                // the filesystem-based DsdFileScanner runs as a parallel supplementary pass
                // and its results are merged here before indexing into Room.
                val dsdFiles = dsdFileScanner.scanDsdFiles()
                val scannedFiles = mediaStoreFiles + dsdFiles
                val totalFiles = scannedFiles.size
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
                    lastIndexedAtEpochMs = System.currentTimeMillis()
                )

                // Atomic Room transaction — must complete before the 100% progress is emitted.
                libraryIndexDao.replaceIndexedLibrary(
                    tracks = trackEntities,
                    albums = albumEntities,
                    artists = artistEntities,
                    state = state
                )

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

    override suspend fun isLibraryIndexed(): Boolean = runCatching {
        libraryIndexDao.getLibraryIndexState()?.isCompleted == true
    }.getOrDefault(false)

    /**
     * Wraps [ContentResolver.registerContentObserver] in a [callbackFlow] so that any addition
     * or removal of audio files in the device MediaStore is pushed as a Flow emission.
     *
     * An initial [Unit] is sent immediately so the first collector receives a value before any
     * change event arrives. [awaitClose] unregisters the observer automatically when the
     * collecting scope is cancelled — no manual lifecycle wiring is required.
     */
    override fun observeMediaStoreChanges(): Flow<Unit> = callbackFlow {
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

