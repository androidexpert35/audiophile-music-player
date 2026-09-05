package com.androidexpert35.audiophilemusicplayer.data.repository

import androidx.core.net.toUri
import com.androidexpert35.audiophilemusicplayer.data.scanner.MusicFolderScope
import com.androidexpert35.audiophilemusicplayer.domain.model.common.LibraryResourceError
import com.androidexpert35.audiophilemusicplayer.domain.model.library.MusicFolder
import com.androidexpert35.audiophilemusicplayer.domain.repository.MusicFolderRepository
import com.tony.coreui.domain.resource.Resource
import com.tony.coreui.domain.resource.ResourceError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [MusicFolderRepository] implementation adapting [MusicFolderRegistry] to the Domain
 * contract.
 *
 * The registry owns the framework side (document-tree URIs and their persisted read
 * grants); this class exists only to map those into [MusicFolder] and to turn failures
 * into [Resource.Error] values so no storage exception reaches the UI.
 *
 * @property registry Source of truth for granted library locations.
 */
@Singleton
class MusicFolderRepositoryImpl @Inject constructor(
    private val registry: MusicFolderRegistry,
) : MusicFolderRepository {

    override fun observeMusicFolders(): Flow<List<MusicFolder>> =
        registry.observeScopes().map { scopes -> scopes.map(MusicFolderScope::toDomain) }

    override suspend fun hasMusicFolders(): Boolean =
        runCatching { registry.getScopes().isNotEmpty() }.getOrDefault(false)

    override suspend fun addMusicFolder(folderId: String): Resource<Unit> {
        val treeUri = runCatching { folderId.toUri() }.getOrNull()
            ?: return Resource.Error(
                LibraryResourceError.UNSUPPORTED_FOLDER
            )

        return try {
            registry.add(treeUri)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            LibraryDiagnostics.record(LibraryResourceError.FOLDER_FAILED, failure)
            Resource.Error(LibraryResourceError.FOLDER_FAILED)
        }
    }

    override suspend fun removeMusicFolder(folderId: String): Resource<Unit> {
        val removed = runCatching { registry.remove(folderId) }.getOrDefault(false)
        return if (removed) {
            Resource.Success(Unit)
        } else {
            Resource.Error(
                ResourceError.StorageError("Unable to stop scanning that folder.")
            )
        }
    }
}

/**
 * Maps a Data-layer scan scope to the Domain model, dropping the framework URI in
 * favour of an opaque identifier so Presentation never handles a `Uri`.
 */
private fun MusicFolderScope.toDomain(): MusicFolder = MusicFolder(
    id = treeUri.toString(),
    displayPath = displayPath,
    storageLabel = storageLabel,
)
