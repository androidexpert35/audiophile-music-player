package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.repository.MusicFolderRepository
import com.tony.coreui.domain.resource.Resource

/**
 * Takes a folder out of the library scan scope and gives up its read grant.
 *
 * Tracks that only existed inside the removed folder disappear from the catalogue on
 * the next scan.
 *
 * @property musicFolderRepository Store of user-granted library locations.
 * @constructor Creates the use case with its required repository dependency.
 */
class RemoveMusicFolderUseCase(
    private val musicFolderRepository: MusicFolderRepository,
) {
    /**
     * Releases the folder grant and removes it from the scan scope.
     *
     * @param folderId Identifier of a currently granted folder.
     * @return [Resource.Success] when the folder is no longer scanned, otherwise
     *   [Resource.Error].
     */
    suspend operator fun invoke(folderId: String): Resource<Unit> =
        musicFolderRepository.removeMusicFolder(folderId)
}
