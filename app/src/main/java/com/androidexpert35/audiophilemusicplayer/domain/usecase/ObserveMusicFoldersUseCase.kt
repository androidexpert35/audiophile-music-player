package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.model.library.MusicFolder
import com.androidexpert35.audiophilemusicplayer.domain.repository.MusicFolderRepository
import kotlinx.coroutines.flow.Flow

/**
 * Observes the storage locations the library scan is currently allowed to read.
 *
 * Emits the persisted collection immediately on subscription, then re-emits after
 * every folder the user adds or removes.
 *
 * @property musicFolderRepository Store of user-granted library locations.
 * @constructor Creates the use case with its required repository dependency.
 */
class ObserveMusicFoldersUseCase(
    private val musicFolderRepository: MusicFolderRepository,
) {
    /**
     * @return [Flow] emitting the current and subsequent sets of granted music folders.
     */
    operator fun invoke(): Flow<List<MusicFolder>> = musicFolderRepository.observeMusicFolders()
}
