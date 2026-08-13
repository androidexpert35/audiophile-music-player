package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.repository.MusicFolderRepository
import com.tony.coreui.domain.resource.Resource

/**
 * Brings a folder the user picked into the scope of the library scan.
 *
 * The read grant taken here is what makes formats Android does not index — notably
 * DSD `.dsf` / `.dff` — readable at all, so adding the folder that holds them is the
 * step that makes those tracks appear in the library.
 *
 * @property musicFolderRepository Store of user-granted library locations.
 * @constructor Creates the use case with its required repository dependency.
 */
class AddMusicFolderUseCase(
    private val musicFolderRepository: MusicFolderRepository,
) {
    /**
     * Persists the granted folder.
     *
     * @param folderId Identifier returned by the system folder chooser.
     * @return [Resource.Success] once the grant is durable, [Resource.Error] when the
     *   grant could not be taken or stored.
     */
    suspend operator fun invoke(folderId: String): Resource<Unit> =
        musicFolderRepository.addMusicFolder(folderId)
}
