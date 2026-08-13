package com.androidexpert35.audiophilemusicplayer.data.scanner

import android.net.Uri

/**
 * One user-granted library location, expressed in both vocabularies the scan needs.
 *
 * A single grant has to drive two very different passes:
 * - [MediaStoreScanner] filters `MediaStore` rows, which are addressed by
 *   ([volumeName], [relativePath]) — never by document URI.
 * - [DsdFileScanner] walks the document tree itself, because DSD containers are absent
 *   from MediaStore and unreachable through `READ_MEDIA_AUDIO`; only [treeUri] can
 *   open them.
 *
 * Holding both forms in one value keeps the two passes provably scoped to the same
 * folder instead of drifting apart.
 *
 * @property treeUri Persisted document-tree URI carrying the long-lived read grant.
 * @property volumeName MediaStore volume the folder lives on, e.g. `external_primary`
 *   for internal storage or `1a2b-3c4d` for a removable card.
 * @property relativePath MediaStore-style path inside the volume, slash-terminated
 *   (`Music/DSD/`). Empty when the user granted the whole volume.
 * @property displayPath Human-readable path without the trailing slash (`Music/DSD`),
 *   blank for a whole-volume grant.
 * @property storageLabel Localised volume description shown next to [displayPath].
 */
data class MusicFolderScope(
    val treeUri: Uri,
    val volumeName: String,
    val relativePath: String,
    val displayPath: String,
    val storageLabel: String,
)
