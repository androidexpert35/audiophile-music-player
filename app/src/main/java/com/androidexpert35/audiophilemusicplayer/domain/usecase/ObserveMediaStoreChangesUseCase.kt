package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.repository.MediaIndexRepository
import kotlinx.coroutines.flow.Flow

/**
 * Observes the device's external audio MediaStore for additions or deletions.
 *
 * Collecting the returned [Flow] subscribes a [android.database.ContentObserver] via the
 * [MediaIndexRepository] contract; the Flow emits [Unit] immediately on collection and once
 * per MediaStore change event thereafter. Callers should debounce rapid bursts before
 * triggering an expensive re-index operation.
 *
 * @property mediaIndexRepository Repository owning the MediaStore observer lifecycle.
 * @constructor Creates the use case with the indexing repository dependency.
 */
class ObserveMediaStoreChangesUseCase(
    private val mediaIndexRepository: MediaIndexRepository
) {
    /**
     * Returns a [Flow] that emits [Unit] on each MediaStore audio content change.
     *
     * The Flow is backed by a [kotlinx.coroutines.channels.callbackFlow] inside the repository,
     * so collection is safe from any coroutine scope; cancellation automatically unregisters
     * the underlying [android.database.ContentObserver].
     *
     * @return Hot [Flow] emitting [Unit] on every detected MediaStore audio change.
     */
    operator fun invoke(): Flow<Unit> = mediaIndexRepository.observeMediaStoreChanges()
}

