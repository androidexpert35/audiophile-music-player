package com.androidexpert35.audiophilemusicplayer.domain.repository

import com.androidexpert35.audiophilemusicplayer.domain.model.library.MusicFolder
import com.tony.coreui.domain.resource.Resource
import kotlinx.coroutines.flow.Flow

/**
 * Repository contract for the set of storage locations the library scan is allowed to read.
 *
 * The scan never walks the whole device: [com.androidexpert35.audiophilemusicplayer.domain.repository.MediaIndexRepository]
 * indexes exactly the folders exposed here, so adding or removing one directly changes
 * which tracks exist in the catalogue.
 */
interface MusicFolderRepository {

    /**
     * Live stream of the granted music folders. Emits the current collection on
     * subscription and again after every addition or removal.
     */
    fun observeMusicFolders(): Flow<List<MusicFolder>>

    /**
     * Reports whether the user has authorised at least one folder.
     *
     * @return `true` when a scan can produce results, `false` while onboarding still
     *   needs to ask the user to pick a folder.
     */
    suspend fun hasMusicFolders(): Boolean

    /**
     * Persists a folder the user picked in the system document-tree chooser and takes
     * a long-lived read grant on it so later scans work without re-prompting.
     *
     * Folders already covered by a previously granted parent are ignored, and any
     * previously granted sub-folder of [folderId] is replaced by it, so the collection
     * never scans the same tree twice.
     *
     * @param folderId Identifier returned by the system folder chooser.
     * @return [Resource.Success] once the grant is durable, otherwise [Resource.Error].
     */
    suspend fun addMusicFolder(folderId: String): Resource<Unit>

    /**
     * Removes a folder from the library scope and releases its read grant.
     *
     * @param folderId Identifier of a folder previously returned by [observeMusicFolders].
     * @return [Resource.Success] when the folder is no longer part of the scan scope,
     *   otherwise [Resource.Error].
     */
    suspend fun removeMusicFolder(folderId: String): Resource<Unit>
}
