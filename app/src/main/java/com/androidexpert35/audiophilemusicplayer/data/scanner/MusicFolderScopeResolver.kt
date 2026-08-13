package com.androidexpert35.audiophilemusicplayer.data.scanner

import android.content.Context
import android.net.Uri
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Translates a document-tree grant into the addressing scheme each scan pass speaks.
 *
 * The system folder chooser hands back a tree URI whose document ID encodes the
 * storage volume and the path inside it (`primary:Music/DSD`). MediaStore, by contrast,
 * addresses the same folder as a volume name plus a slash-terminated relative path
 * (`external_primary` + `Music/DSD/`). This resolver is the single place that bridges
 * the two, so a folder the user picks filters MediaStore rows and drives the document
 * walk consistently.
 *
 * @property context Application context used to resolve localised storage-volume names.
 */
@Singleton
class MusicFolderScopeResolver @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    /**
     * Resolves a persisted tree URI into a fully-addressed [MusicFolderScope].
     *
     * @param treeUri Document-tree URI previously returned by the system folder chooser.
     * @return The resolved scope, or `null` when the URI is not a document tree or its
     *   document ID does not follow the `volume:path` form used by external storage.
     */
    fun resolve(treeUri: Uri): MusicFolderScope? = runCatching {
        val documentId = DocumentsContract.getTreeDocumentId(treeUri) ?: return null
        val separatorIndex = documentId.indexOf(':')
        if (separatorIndex < 0) return null

        val volumeId = documentId.substring(0, separatorIndex)
        val displayPath = documentId.substring(separatorIndex + 1).trim('/')
        val volumeName = toMediaStoreVolumeName(volumeId)

        MusicFolderScope(
            treeUri = treeUri,
            volumeName = volumeName,
            relativePath = if (displayPath.isEmpty()) "" else "$displayPath/",
            displayPath = displayPath,
            storageLabel = resolveStorageLabel(volumeName),
        )
    }.getOrNull()

    /**
     * Maps a Storage Access Framework volume ID to its MediaStore counterpart.
     *
     * The chooser reports the primary volume as `primary`, while MediaStore calls it
     * [MediaStore.VOLUME_EXTERNAL_PRIMARY]. Removable volumes share the same UUID in
     * both APIs, but MediaStore stores it lower-cased.
     */
    private fun toMediaStoreVolumeName(volumeId: String): String =
        if (volumeId.equals(PRIMARY_VOLUME_ID, ignoreCase = true)) {
            MediaStore.VOLUME_EXTERNAL_PRIMARY
        } else {
            volumeId.lowercase()
        }

    /**
     * Resolves the localised description of the volume the folder lives on so the
     * Settings list can distinguish `Music` on internal storage from `Music` on a card.
     *
     * Falls back to the raw MediaStore volume name when the volume has been unmounted
     * since the grant was taken.
     */
    private fun resolveStorageLabel(volumeName: String): String {
        val storageManager = context.getSystemService(StorageManager::class.java)
        val description = storageManager?.storageVolumes
            ?.firstOrNull { volume -> volume.mediaStoreVolumeName == volumeName }
            ?.getDescription(context)
        return description?.takeIf { it.isNotBlank() } ?: volumeName
    }

    private companion object {
        /** Document-tree volume ID the framework uses for primary external storage. */
        const val PRIMARY_VOLUME_ID = "primary"
    }
}
