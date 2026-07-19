package com.androidexpert35.audiophilemusicplayer.domain.repository

import com.androidexpert35.audiophilemusicplayer.domain.model.indexing.MediaIndexingProgress
import com.tony.coreui.domain.resource.Resource
import kotlinx.coroutines.flow.Flow

/**
 * Repository contract for the one-time MediaStore scan that builds the cached Room library index,
 * and for observing future MediaStore mutations so new audio files are detected automatically.
 */
interface MediaIndexRepository {

    /**
     * Scans device audio files and persists a fresh indexed-library snapshot.
     *
     * @return A [Flow] emitting progress updates wrapped in [Resource] so the UI can display
     *         indexing progress and surface recoverable errors.
     */
    fun scanAndIndexMedia(): Flow<Resource<MediaIndexingProgress>>

    /**
     * Indicates whether the initial Room-backed media index has already completed successfully.
     *
     * @return `true` when onboarding can be skipped on launch, `false` otherwise.
     */
    suspend fun isLibraryIndexed(): Boolean

    /**
     * Observes changes to the device's external audio MediaStore table.
     *
     * Emits [Unit] once immediately on collection and then every time the underlying
     * MediaStore content changes (e.g. a new audio file is added or deleted). Callers
     * should debounce rapid bursts before triggering an expensive re-index.
     *
     * @return A [Flow] that emits whenever the audio library on the device changes.
     */
    fun observeMediaStoreChanges(): Flow<Unit>
}

