package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.repository.MediaIndexRepository

/**
 * Determines whether the initial onboarding scan has already produced a cached Room library index.
 *
 * @property mediaIndexRepository Repository exposing the persisted indexing completion flag.
 * @constructor Creates the use case with the required repository dependency.
 */
class IsMediaLibraryIndexedUseCase(
    private val mediaIndexRepository: MediaIndexRepository
) {
    /**
     * @return `true` when the app can skip the onboarding scan, otherwise `false`.
     */
    suspend operator fun invoke(): Boolean = mediaIndexRepository.isLibraryIndexed()
}

