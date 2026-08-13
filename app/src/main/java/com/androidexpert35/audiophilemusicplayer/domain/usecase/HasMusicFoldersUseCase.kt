package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.repository.MusicFolderRepository

/**
 * Determines whether the user has already chosen where their music lives.
 *
 * Onboarding cannot start indexing before at least one folder is granted, because the
 * scan is scoped to those folders and would otherwise produce an empty library.
 *
 * @property musicFolderRepository Store of user-granted library locations.
 * @constructor Creates the use case with its required repository dependency.
 */
class HasMusicFoldersUseCase(
    private val musicFolderRepository: MusicFolderRepository,
) {
    /**
     * @return `true` when at least one music folder is granted, otherwise `false`.
     */
    suspend operator fun invoke(): Boolean = musicFolderRepository.hasMusicFolders()
}
