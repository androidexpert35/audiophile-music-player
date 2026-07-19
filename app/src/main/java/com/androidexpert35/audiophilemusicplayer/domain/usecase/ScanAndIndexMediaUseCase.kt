package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.model.indexing.MediaIndexingProgress
import com.androidexpert35.audiophilemusicplayer.domain.repository.MediaIndexRepository
import com.tony.coreui.domain.resource.Resource
import kotlinx.coroutines.flow.Flow

/**
 * Executes the initial MediaStore scan and persists its results into the Room-backed library cache.
 *
 * @property mediaIndexRepository Repository coordinating MediaStore scanning and Room writes.
 * @constructor Creates the use case with the indexing repository dependency.
 */
class ScanAndIndexMediaUseCase(
    private val mediaIndexRepository: MediaIndexRepository
) {
    /**
     * Starts the indexing workflow.
     *
     * @return Progress emissions wrapped in [Resource] so the presentation layer can react to
     *         both incremental updates and indexing failures.
     */
    operator fun invoke(): Flow<Resource<MediaIndexingProgress>> =
        mediaIndexRepository.scanAndIndexMedia()
}

