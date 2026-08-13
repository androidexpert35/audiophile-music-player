package com.androidexpert35.audiophilemusicplayer.data.scanner

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import com.androidexpert35.audiophilemusicplayer.data.local.entity.ImportedPlaylistEntity
import com.androidexpert35.audiophilemusicplayer.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Supplementary scanner that discovers `.m3u` / `.m3u8` playlist files inside the folders
 * the user granted as music locations, so playlists a listener already has on disk (e.g. a
 * `Music/Playlist` folder synced from another player) surface in the app automatically.
 *
 * **Why a separate scanner is needed:**
 * Android's system media scanner never registers `.m3u`/`.m3u8` as an audio MIME type, so
 * these files never appear as `MediaStore` rows regardless of folder scope — the same reason
 * [DsdFileScanner] exists for `.dsf`/`.dff`. This scanner walks the same granted document
 * trees directly.
 *
 * **Track resolution:**
 * Each non-comment line in a playlist is a path — absolute, or relative to the playlist
 * file's own folder — rather than a `content://` URI. Entries are matched against the
 * [ScannedAudioFile] records already produced by the same scan pass (MediaStore's absolute
 * `DATA` paths and DSD's grant-relative paths) by normalized path comparison, falling back to
 * a suffix match and finally an unambiguous filename match. Unresolved entries are skipped —
 * a playlist referencing one moved or deleted file still shows its other tracks.
 *
 * @property contentResolver Resolver used to walk granted trees and open documents.
 * @property ioDispatcher Background dispatcher for all blocking I/O operations.
 */
@Singleton
class M3uFileScanner @Inject constructor(
    private val contentResolver: ContentResolver,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {

    /**
     * Scans every granted music folder for `.m3u`/`.m3u8` documents and resolves their
     * entries against [scannedFiles].
     *
     * @param folders Locations the user authorised as music folders. An empty list yields
     *   an empty result — there is no whole-device fallback.
     * @param scannedFiles Audio files already discovered in the same scan pass (MediaStore
     *   plus DSD), used to resolve each playlist entry's path to a content URI.
     * @return List of [ImportedPlaylistEntity] records, one per discovered playlist file
     *   that contains at least one line (empty playlists are still recorded).
     */
    suspend fun scanPlaylists(
        folders: List<MusicFolderScope>,
        scannedFiles: List<ScannedAudioFile>,
    ): List<ImportedPlaylistEntity> = withContext(ioDispatcher) {
        val index = TrackPathIndex(scannedFiles)
        val results = mutableListOf<ImportedPlaylistEntity>()
        val visitedDocumentIds = mutableSetOf<String>()

        for (folder in folders) {
            val rootDocumentId = runCatching {
                DocumentsContract.getTreeDocumentId(folder.treeUri)
            }.getOrNull() ?: continue

            walkForPlaylists(
                folder = folder,
                documentId = rootDocumentId,
                displayPath = folder.displayPath,
                visitedDocumentIds = visitedDocumentIds,
                index = index,
                results = results,
            )
        }

        results
    }

    /**
     * Recursively walks the document tree rooted at [documentId], collecting playlist files.
     *
     * Mirrors [DsdFileScanner]'s walk: failures on a single document are swallowed so one
     * unreadable playlist doesn't abort the scan, hidden directories and the `Android` data
     * tree are skipped, and each document ID is visited once.
     */
    private suspend fun walkForPlaylists(
        folder: MusicFolderScope,
        documentId: String,
        displayPath: String,
        visitedDocumentIds: MutableSet<String>,
        index: TrackPathIndex,
        results: MutableList<ImportedPlaylistEntity>,
    ) {
        if (!visitedDocumentIds.add(documentId)) return
        coroutineContext.ensureActive()

        val childrenUri = runCatching {
            DocumentsContract.buildChildDocumentsUriUsingTree(folder.treeUri, documentId)
        }.getOrNull() ?: return

        val childDirectories = mutableListOf<Pair<String, String>>()

        runCatching {
            contentResolver.query(childrenUri, CHILD_PROJECTION, null, null, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val modifiedIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                while (cursor.moveToNext()) {
                    val childId = cursor.getString(idIndex) ?: continue
                    val childName = cursor.getString(nameIndex).orEmpty()
                    val childMime = cursor.getString(mimeIndex).orEmpty()
                    val childPath = if (displayPath.isEmpty()) childName else "$displayPath/$childName"

                    if (childMime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (childName.startsWith(".") || childName == ANDROID_DATA_DIR) continue
                        childDirectories += childId to childPath
                        continue
                    }

                    if (childName.substringAfterLast('.', "").lowercase() !in PLAYLIST_EXTENSIONS) continue

                    val documentUri = DocumentsContract.buildDocumentUriUsingTree(folder.treeUri, childId)
                    scanSinglePlaylist(
                        documentUriString = documentUri.toString(),
                        displayName = childName,
                        parentDisplayPath = displayPath,
                        lastModifiedMs = cursor.getLong(modifiedIndex),
                        index = index,
                    )?.let { results += it }
                }
            }
        }

        for ((childId, childPath) in childDirectories) {
            walkForPlaylists(folder, childId, childPath, visitedDocumentIds, index, results)
        }
    }

    /**
     * Reads and parses one playlist document, resolving its entries via [index].
     *
     * @param documentUriString Document URI of the playlist, reused as its stable ID.
     * @param displayName File name including extension.
     * @param parentDisplayPath Path of the playlist's containing folder, used to resolve
     *   relative entries.
     * @param lastModifiedMs Document modification time reported by the provider.
     * @return Populated [ImportedPlaylistEntity], or `null` if the document can't be read.
     */
    private fun scanSinglePlaylist(
        documentUriString: String,
        displayName: String,
        parentDisplayPath: String,
        lastModifiedMs: Long,
        index: TrackPathIndex,
    ): ImportedPlaylistEntity? = runCatching {
        val lines = contentResolver.openInputStream(Uri.parse(documentUriString))
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { it.readLines() }
            ?: return@runCatching null

        val name = lines.firstOrNull { it.startsWith(PLAYLIST_NAME_PREFIX) }
            ?.removePrefix(PLAYLIST_NAME_PREFIX)
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: displayName.substringBeforeLast('.')

        val trackUris = lines
            .filter { line -> line.isNotBlank() && !line.startsWith("#") }
            .mapNotNull { entry -> index.resolve(entry.trim(), parentDisplayPath) }

        ImportedPlaylistEntity(
            documentUri = documentUriString,
            name = name,
            trackUris = trackUris,
            lastModifiedMs = lastModifiedMs,
        )
    }.getOrNull()

    /**
     * Resolves playlist entry paths against the audio files discovered in the same scan
     * pass, using progressively looser matching.
     *
     * Internal (rather than private) so its matching rules are directly unit-testable without
     * driving the `DocumentsContract` tree walk.
     */
    internal class TrackPathIndex(scannedFiles: List<ScannedAudioFile>) {

        private data class Candidate(val segments: List<String>, val contentUri: String)

        private val candidates: List<Candidate> = scannedFiles.map { file ->
            Candidate(segments(file.filePath), file.contentUri)
        }

        private val contentUris: Set<String> = scannedFiles.mapTo(mutableSetOf()) { it.contentUri }

        /**
         * Resolves one playlist entry line to a content URI.
         *
         * @param entry Raw playlist line (a path, or occasionally already a URI).
         * @param parentDisplayPath Directory the playlist file lives in, used to resolve a
         *   relative [entry].
         * @return The matching track's content URI, or `null` if no confident match exists.
         */
        fun resolve(entry: String, parentDisplayPath: String): String? {
            if (entry in contentUris) return entry

            val normalizedEntry = entry.replace('\\', '/')
            val entrySegments = if (normalizedEntry.startsWith("/")) {
                segments(normalizedEntry)
            } else {
                segments(if (parentDisplayPath.isEmpty()) normalizedEntry else "$parentDisplayPath/$normalizedEntry")
            }
            if (entrySegments.isEmpty()) return null

            // Exact path match (e.g. both sides are absolute MediaStore DATA paths).
            candidates.firstOrNull { it.segments.equalsIgnoreCase(entrySegments) }
                ?.let { return it.contentUri }

            // Suffix match: one side (typically the relative entry) trails the other
            // (typically an absolute MediaStore path), in either direction.
            candidates.firstOrNull { candidate ->
                endsWith(candidate.segments, entrySegments) || endsWith(entrySegments, candidate.segments)
            }?.let { return it.contentUri }

            // Filename-only fallback, only when it uniquely identifies one candidate —
            // an ambiguous filename is left unresolved rather than risking the wrong track.
            val entryFileName = entrySegments.last()
            val filenameMatches = candidates.filter { candidate ->
                candidate.segments.lastOrNull()?.equals(entryFileName, ignoreCase = true) == true
            }
            return filenameMatches.singleOrNull()?.contentUri
        }

        /**
         * Splits a path into normalized segments, collapsing `.` and `..` — extended-M3U
         * entries commonly climb out of the playlist's own folder (`../Artist/Song.flac`),
         * and leaving those segments unresolved would defeat the suffix match below.
         */
        private fun segments(path: String): List<String> {
            val stack = ArrayDeque<String>()
            for (segment in path.replace('\\', '/').split('/')) {
                when (segment) {
                    "", "." -> Unit
                    ".." -> if (stack.isNotEmpty()) stack.removeLast() else Unit
                    else -> stack.addLast(segment)
                }
            }
            return stack
        }

        private fun List<String>.equalsIgnoreCase(other: List<String>): Boolean =
            size == other.size && indices.all { i -> this[i].equals(other[i], ignoreCase = true) }

        private fun endsWith(longer: List<String>, shorter: List<String>): Boolean =
            longer.size >= shorter.size &&
                longer.takeLast(shorter.size).let { tail ->
                    tail.indices.all { i -> tail[i].equals(shorter[i], ignoreCase = true) }
                }
    }

    private companion object {
        private val PLAYLIST_EXTENSIONS = setOf("m3u", "m3u8")
        private const val PLAYLIST_NAME_PREFIX = "#PLAYLIST:"

        /** Directory name of the Android app-data tree, never walked for user media. */
        private const val ANDROID_DATA_DIR = "Android"

        /** Columns read for every child document while walking a granted tree. */
        private val CHILD_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}
