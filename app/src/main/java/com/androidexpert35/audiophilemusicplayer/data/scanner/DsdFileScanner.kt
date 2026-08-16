package com.androidexpert35.audiophilemusicplayer.data.scanner

import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import com.androidexpert35.audiophilemusicplayer.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Supplementary scanner that discovers DSD audio files (`.dsf`, `.dff`) inside the
 * folders the user granted as music locations.
 *
 * **Why a separate scanner is needed:**
 * Android's system media scanner does not register DSD formats in
 * `MediaStore.Audio.Media` because the OS has no built-in DSD decoder and therefore
 * no MIME type entry for these containers. DSD files placed in the Music folder are
 * completely invisible to any `ContentResolver` query against MediaStore — they are
 * never inserted as rows in the first place.
 *
 * **Why it walks document trees rather than the filesystem:**
 * For the same reason, the platform does not treat `.dsf` / `.dff` as audio, so
 * `READ_MEDIA_AUDIO` grants no access to them: a direct `File` walk of shared storage
 * cannot even see these files on the API levels this app supports. The only durable way
 * to read them is the persisted document-tree grant the user hands over when adding a
 * music folder, which is why DSD tracks appear in the library exactly once such a folder
 * has been added.
 *
 * The scanner therefore:
 * 1. Walks each granted [MusicFolderScope] tree through [DocumentsContract], skipping
 *    hidden directories and the `Android` data tree.
 * 2. Attempts [MediaMetadataRetriever] for tag-based metadata first.
 * 3. **Falls back to direct ID3v2 binary parsing** for DSF files when MMR fails —
 *    necessary on most Android OEMs where the platform codec framework has no DSD
 *    decoder and MMR returns null for every metadata key despite opening the file.
 * 4. Extracts embedded album art (APIC frame) and caches it as a `file://` URI in
 *    the app's cache directory so Coil can load it without MediaStore involvement.
 * 5. Parses the binary DSF / DFF header directly to recover duration and sample rate.
 * 6. Produces [ScannedAudioFile] records carrying the document URI, which both the
 *    audiophile engine and Media3 can open directly for playback.
 *
 * Stable track IDs are generated from each document URI's hash, using a negative Long
 * range so they cannot collide with positive MediaStore IDs.
 *
 * @property context Application context used for metadata retrieval and for writing
 *   cached artwork to [Context.getCacheDir].
 * @property contentResolver Resolver used to walk granted trees and open documents.
 * @property ioDispatcher Background dispatcher for all blocking I/O operations.
 */
@Singleton
class DsdFileScanner @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val contentResolver: ContentResolver,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {

    /**
     * Scans every granted music folder for `.dsf` and `.dff` documents.
     *
     * @param folders Locations the user authorised as music folders. An empty list yields
     *   an empty result — there is no whole-device fallback.
     * @return List of [ScannedAudioFile] records representing discovered DSD tracks,
     *   sorted by title. Returns an empty list when no DSD files are found.
     */
    suspend fun scanDsdFiles(folders: List<MusicFolderScope>): List<ScannedAudioFile> =
        withContext(ioDispatcher) {
            val results = mutableListOf<ScannedAudioFile>()
            val visitedDocumentIds = mutableSetOf<String>()

            for (folder in folders) {
                val rootDocumentId = runCatching {
                    DocumentsContract.getTreeDocumentId(folder.treeUri)
                }.getOrNull() ?: continue

                walkForDsd(
                    folder = folder,
                    documentId = rootDocumentId,
                    displayPath = folder.displayPath,
                    visitedDocumentIds = visitedDocumentIds,
                    results = results,
                )
            }

            results.sortedBy { it.title }
        }

    /**
     * Recursively walks the document tree rooted at [documentId], collecting DSD files.
     *
     * Each document is processed in isolation — a failure in [scanSingleDocument] is
     * swallowed so one corrupt file does not abort the walk. Directories starting with
     * `.` (hidden) or named `Android` (system data tree) are always skipped, and every
     * document ID is visited once so a tree reachable through two grants is not indexed
     * twice.
     *
     * @param folder Granted folder currently being walked.
     * @param documentId Directory document ID to enumerate.
     * @param displayPath Human-readable path of [documentId] used for scan feedback.
     * @param visitedDocumentIds Document IDs already processed.
     * @param results Accumulator for discovered [ScannedAudioFile] records.
     */
    private suspend fun walkForDsd(
        folder: MusicFolderScope,
        documentId: String,
        displayPath: String,
        visitedDocumentIds: MutableSet<String>,
        results: MutableList<ScannedAudioFile>,
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
                val sizeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                val modifiedIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                while (cursor.moveToNext()) {
                    val childId = cursor.getString(idIndex) ?: continue
                    val childName = cursor.getString(nameIndex).orEmpty()
                    val childMime = cursor.getString(mimeIndex).orEmpty()
                    val childPath = if (displayPath.isEmpty()) childName else "$displayPath/$childName"

                    if (childMime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (childName.startsWith(".") || childName == ANDROID_DATA_DIR) continue
                        // Collected first so the cursor is closed before recursing, keeping
                        // at most one open cursor per tree level instead of one per depth.
                        childDirectories += childId to childPath
                        continue
                    }

                    if (childName.substringAfterLast('.', "").lowercase() !in DSD_EXTENSIONS) continue

                    val documentUri = DocumentsContract.buildDocumentUriUsingTree(folder.treeUri, childId)
                    scanSingleDocument(
                        documentUri = documentUri,
                        displayName = childName,
                        displayPath = childPath,
                        fileSizeBytes = cursor.getLong(sizeIndex),
                        lastModifiedMs = cursor.getLong(modifiedIndex),
                    )?.let { results += it }
                }
            }
        }

        for ((childId, childPath) in childDirectories) {
            walkForDsd(folder, childId, childPath, visitedDocumentIds, results)
        }
    }

    /**
     * Extracts metadata for a single DSD document and converts it to a [ScannedAudioFile].
     *
     * **Metadata resolution order:**
     * 1. [MediaMetadataRetriever] — fast and sufficient on Android versions /
     *    OEMs that recognise DSF in the platform codec framework.
     * 2. Direct ID3v2 binary parsing (DSF only) — used when MMR returns blank
     *    values or throws. Seeks to the ID3 offset recorded in the DSF chunk
     *    header and reads TIT2 / TPE1 / TALB / TRCK / TDRC frames directly.
     * 3. Binary DSD / DFF header parsing for duration and sample rate.
     *
     * **Album art resolution order:**
     * 1. [MediaMetadataRetriever.getEmbeddedPicture] — available when the platform
     *    codec stack handles DSF / DFF.
     * 2. `APIC` frame extracted from the DSF ID3v2 tag binary parse.
     *
     * Extracted art bytes are written once to `<cacheDir>/dsd_art/` and the
     * resulting `file://` URI is stored in [ScannedAudioFile.artUri] so Coil can
     * load album artwork for DSD tracks the same way as regular MediaStore tracks.
     *
     * @param documentUri Document URI of the candidate DSD file.
     * @param displayName File name including extension.
     * @param displayPath Path of the document relative to its storage volume.
     * @param fileSizeBytes Document size reported by the provider.
     * @param lastModifiedMs Document modification time in epoch milliseconds.
     * @return Populated [ScannedAudioFile], or `null` if the document is unreadable or has
     *   a duration below the 30-second threshold used for MediaStore tracks.
     */
    private fun scanSingleDocument(
        documentUri: Uri,
        displayName: String,
        displayPath: String,
        fileSizeBytes: Long,
        lastModifiedMs: Long,
    ): ScannedAudioFile? = runCatching {
        val uriString = documentUri.toString()
        // Derive a stable, collision-free ID from the document URI. MediaStore IDs are
        // always positive; negating the hash pushes these IDs into the negative range.
        val stableId = -(uriString.hashCode().toLong().and(0x7FFF_FFFFL) + 1L)
        val isDsf = displayName.substringAfterLast('.', "").equals("dsf", ignoreCase = true)

        var title = displayName.substringBeforeLast('.')
        var artist = UNKNOWN_ARTIST
        var album = UNKNOWN_ALBUM
        var durationMs = 0L
        var trackNumber = 0
        var year = 0
        var genre: String? = null
        var composer: String? = null
        var sampleRateHz = 0
        var embeddedPicture: ByteArray? = null

        // ── Phase 1: tag-based metadata via MediaMetadataRetriever ───────────
        // MMR works for DSF on some Android versions (especially stock AOSP) but
        // fails silently — returning nulls instead of throwing — on many OEM
        // firmware builds that lack a DSD codec entry in the platform stack.
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(context, documentUri)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() }?.let { title = it }
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() }?.let { artist = it }
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?.takeIf { it.isNotBlank() }?.let { album = it }
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.takeIf { it > 0 }?.let { durationMs = it }
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                ?.substringBefore('/')?.toIntOrNull()?.let { trackNumber = it }
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
                ?.toIntOrNull()?.let { year = it }
            genre = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            composer = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            embeddedPicture = try { mmr.getEmbeddedPicture() } catch (_: Exception) { null }
        } catch (_: Exception) {
            // MMR silently fails on some DFF files; header parsing below is the fallback.
        } finally {
            runCatching { mmr.release() }
        }

        // ── Phase 2: direct binary parsing ───────────────────────────────────
        // A single descriptor serves both the ID3v2 tag read and the container
        // header parse, so a track costs one open regardless of how much MMR missed.
        SeekableDocumentSource.open(contentResolver, documentUri)?.use { source ->
            // Phase 2a: ID3v2 binary parsing (DSF only).
            // DSF files embed an ID3v2 tag whose file offset is stored at bytes 20–27
            // of the DSD chunk header (little-endian int64). When MMR returns blank
            // values — which happens on most Samsung-derived Android and many OEMs —
            // we parse TIT2, TPE1, TALB, TRCK, TDRC, and APIC frames directly from the
            // raw bytes. This path is reliable on every Android version because it
            // requires only positioned reads, not a platform codec decoder.
            if (isDsf) {
                val id3Offset = readDsfId3Offset(source)
                if (id3Offset > 0L) {
                    val id3 = parseId3v2Tag(source, id3Offset)
                    // Only promote direct-parsed values when MMR left them as defaults.
                    if (title == displayName.substringBeforeLast('.')) id3.title?.let { title = it }
                    if (artist == UNKNOWN_ARTIST) id3.artist?.let { artist = it }
                    if (album == UNKNOWN_ALBUM) id3.album?.let { album = it }
                    if (trackNumber == 0) id3.trackNumber.takeIf { it > 0 }?.let { trackNumber = it }
                    if (year == 0) id3.year.takeIf { it > 0 }?.let { year = it }
                    if (genre == null) genre = id3.genre
                    if (composer == null) composer = id3.composer
                    if (embeddedPicture == null) embeddedPicture = id3.pictureBytes
                }
            }

            // Phase 2b: container header — the only source of DSD sample rate, and the
            // duration fallback when MMR could not decode the stream.
            val headerData = readHeaderBytes(source)
            if (headerData != null) {
                val header = when {
                    isDsfMagic(headerData) -> parseDsfHeader(headerData)
                    isDffMagic(headerData) -> parseDffHeader(source)
                    else -> null
                }
                if (header != null) {
                    sampleRateHz = header.sampleRateHz
                    if (durationMs <= 0L) durationMs = header.durationMs
                }
            }
        }

        // Skip files shorter than 30 seconds, matching the MediaStore duration filter.
        if (durationMs < MIN_DURATION_MS) return@runCatching null

        // ── Phase 3: Persist embedded art to the app cache ───────────────────
        // MediaStore's albumart provider has no knowledge of DSD files, so any
        // embedded APIC picture must be written to local storage and referenced
        // by a file:// URI. Using cacheDir is intentional: the art is re-extracted
        // on the next indexing pass if Android reclaims the cache.
        val artUri = embeddedPicture?.let { saveEmbeddedArtToCache(it) }

        ScannedAudioFile(
            id = stableId,
            title = title,
            artistId = -(artist.hashCode().toLong().and(0x7FFF_FFFFL) + 1L),
            artistName = artist,
            albumId = -(album.hashCode().toLong().and(0x7FFF_FFFFL) + 1L),
            albumTitle = album,
            durationMs = durationMs,
            contentUri = uriString,
            filePath = displayPath,
            trackNumber = trackNumber,
            discNumber = 1,
            mimeType = MIME_DSD,
            fileSizeBytes = fileSizeBytes,
            dateAdded = lastModifiedMs / 1_000L,
            year = year,
            artUri = artUri,
            // DSD sample rates: DSD64 = 2 822 400 Hz, DSD128 = 5 644 800, DSD256 = 11 289 600.
            // Read from the binary header since MMR does not expose DSD sample rate.
            sampleRateHz = sampleRateHz,
            bitDepth = 1, // DSD is inherently 1-bit; mark explicitly for display purposes.
            genre = genre,
            composer = composer,
        )
    }.getOrNull()

    // ── DSF ID3v2 offset extraction ───────────────────────────────────────────

    /**
     * Reads the ID3v2 tag offset stored at bytes 20–27 (little-endian `int64`) of
     * the DSF chunk header. Returns −1 if the document cannot be read or has no tag.
     */
    private fun readDsfId3Offset(source: SeekableDocumentSource): Long = runCatching {
        if (source.length() < 28) return@runCatching -1L
        source.seek(20)
        val buf = ByteArray(8)
        source.readFully(buf)
        ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).long
    }.getOrDefault(-1L)

    // ── Direct ID3v2 tag parser ───────────────────────────────────────────────

    /**
     * Result of a direct ID3v2 binary tag parse, holding the fields relevant for
     * library indexing and embedded album art.
     *
     * @property title Track title from the TIT2 frame, or `null` when absent.
     * @property artist Artist name from the TPE1 frame, or `null` when absent.
     * @property album Album title from the TALB frame, or `null` when absent.
     * @property trackNumber Track number from the TRCK frame, `0` when absent.
     * @property year Release year from TDRC or TYER, `0` when absent.
     * @property genre Genre from TCON, or `null` when absent.
     * @property composer Composer from TCOM, or `null` when absent.
     * @property pictureBytes Raw JPEG/PNG bytes from the first APIC frame, or `null`.
     */
    private class Id3TagResult(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val trackNumber: Int = 0,
        val year: Int = 0,
        val genre: String? = null,
        val composer: String? = null,
        val pictureBytes: ByteArray? = null,
    )

    /**
     * Parses the ID3v2 tag at [offset] inside [source], extracting text frames and
     * the first APIC (attached picture) frame.
     *
     * Supports ID3v2.3 and v2.4. Frame sizes are plain big-endian int32 in v2.3
     * and syncsafe int32 in v2.4. Text encoding is honoured for both ASCII/Latin-1
     * and UTF-8/UTF-16.
     *
     * @param source Open reader over the DSF document containing the tag.
     * @param offset Byte offset of the `ID3` magic within the document.
     * @return Parsed [Id3TagResult]; all fields are null/zero on parse failure.
     */
    private fun parseId3v2Tag(source: SeekableDocumentSource, offset: Long): Id3TagResult = runCatching {
        source.seek(offset)

        // Verify ID3v2 magic.
        val magic = ByteArray(3).also { source.readFully(it) }
        if (String(magic, Charsets.US_ASCII) != "ID3") return@runCatching Id3TagResult()

        val versionMajor = source.readUnsignedByte()  // 3 = v2.3, 4 = v2.4
        source.readUnsignedByte()                      // revision (ignored)
        val flags = source.readUnsignedByte()
        val hasExtendedHeader = (flags and 0x40) != 0

        // Tag size is always a syncsafe int32 (7-bit bytes).
        val tagSize = readSyncsafeInt(source)
        val tagEnd = offset + 10L + tagSize

        // Skip the optional extended header.
        if (hasExtendedHeader) {
            val extHeaderSize = if (versionMajor == 4) readSyncsafeInt(source) else readInt32BE(source)
            // extHeaderSize includes its own 4-byte field; skip the remaining content.
            val remaining = (extHeaderSize - 4).coerceAtLeast(0)
            if (remaining > 0) source.skipBytes(remaining)
        }

        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var trackNumber = 0
        var year = 0
        var genre: String? = null
        var composer: String? = null
        var pictureBytes: ByteArray? = null

        // Iterate frames until padding (all-zero ID) or tag boundary.
        while (source.position < tagEnd - 10 && source.position < source.length() - 10) {
            val frameIdBytes = ByteArray(4).also { source.readFully(it) }
            val frameId = String(frameIdBytes, Charsets.US_ASCII)

            // All-zero ID bytes signal the start of tag padding — stop here.
            if (frameIdBytes.all { it == 0.toByte() }) break

            val frameSize = if (versionMajor == 4) readSyncsafeInt(source) else readInt32BE(source)
            source.readUnsignedByte()  // frame flags byte 1 (ignored)
            source.readUnsignedByte()  // frame flags byte 2 (ignored)

            // Guard against absurd frame sizes (corrupt tag or seek to wrong offset).
            if (frameSize <= 0 || frameSize > MAX_FRAME_BYTES) break

            val frameData = ByteArray(frameSize).also { source.readFully(it) }

            when (frameId) {
                "TIT2" -> if (title == null) title = decodeId3TextFrame(frameData)
                "TPE1" -> if (artist == null) artist = decodeId3TextFrame(frameData)
                "TALB" -> if (album == null) album = decodeId3TextFrame(frameData)
                "TRCK" -> if (trackNumber == 0)
                    trackNumber = decodeId3TextFrame(frameData)
                        ?.substringBefore('/')?.toIntOrNull() ?: 0
                "TDRC", "TYER" -> if (year == 0)
                    year = decodeId3TextFrame(frameData)?.take(4)?.toIntOrNull() ?: 0
                "TCON" -> if (genre == null) genre = decodeId3TextFrame(frameData)
                "TCOM" -> if (composer == null) composer = decodeId3TextFrame(frameData)
                "APIC" -> if (pictureBytes == null) pictureBytes = extractApicBytes(frameData)
            }
        }

        Id3TagResult(title, artist, album, trackNumber, year, genre, composer, pictureBytes)
    }.getOrDefault(Id3TagResult())

    /**
     * Reads a 4-byte syncsafe integer from [source].
     *
     * ID3v2 syncsafe integers use only the lower 7 bits of each byte, giving a
     * 28-bit effective range. Used for the tag header size and all v2.4 frame sizes.
     */
    private fun readSyncsafeInt(source: SeekableDocumentSource): Int {
        val b = ByteArray(4).also { source.readFully(it) }
        return ((b[0].toInt() and 0x7F) shl 21) or
            ((b[1].toInt() and 0x7F) shl 14) or
            ((b[2].toInt() and 0x7F) shl 7) or
            (b[3].toInt() and 0x7F)
    }

    /**
     * Decodes an ID3v2 text frame payload, honouring the encoding byte (byte 0).
     *
     * | Encoding byte | Charset         |
     * |---------------|-----------------|
     * | 0             | ISO-8859-1      |
     * | 1             | UTF-16 with BOM |
     * | 2             | UTF-16BE        |
     * | 3             | UTF-8           |
     *
     * @param data Raw frame bytes including the leading encoding byte.
     * @return Trimmed string, or `null` when the result is blank or data is empty.
     */
    private fun decodeId3TextFrame(data: ByteArray): String? {
        if (data.isEmpty()) return null
        val enc = data[0].toInt() and 0xFF
        val payload = data.copyOfRange(1, data.size)
        val raw = when (enc) {
            0 -> payload.toString(Charsets.ISO_8859_1)
            1 -> if (payload.size >= 2) payload.toString(Charsets.UTF_16) else return null
            2 -> payload.toString(Charsets.UTF_16BE)
            3 -> payload.toString(Charsets.UTF_8)
            else -> return null
        }
        return raw.trimEnd('\u0000').trim().takeIf { it.isNotBlank() }
    }

    /**
     * Extracts the raw picture bytes from an APIC frame payload.
     *
     * APIC layout (after frame header):
     * ```
     * [enc byte] [MIME type, null-term] [picture type byte]
     * [description, null-term with enc-awareness] [picture data]
     * ```
     *
     * @param data Raw APIC frame bytes (excluding the 10-byte frame header).
     * @return JPEG or PNG bytes, or `null` when the frame is malformed.
     */
    private fun extractApicBytes(data: ByteArray): ByteArray? {
        if (data.size < 4) return null
        val enc = data[0].toInt() and 0xFF

        // Skip MIME type — ASCII null-terminated.
        var pos = 1
        while (pos < data.size && data[pos] != 0.toByte()) pos++
        pos++ // consume null terminator
        if (pos >= data.size) return null

        pos++ // skip picture type byte

        // Skip description — null termination is 1 byte for Latin-1/UTF-8,
        // 2 bytes (0x00 0x00) for UTF-16.
        if (enc == 1 || enc == 2) {
            while (pos + 1 < data.size) {
                if (data[pos] == 0.toByte() && data[pos + 1] == 0.toByte()) {
                    pos += 2
                    break
                }
                pos += 2
            }
        } else {
            while (pos < data.size && data[pos] != 0.toByte()) pos++
            pos++ // consume null terminator
        }

        return if (pos < data.size) data.copyOfRange(pos, data.size) else null
    }

    // ── Embedded art caching ──────────────────────────────────────────────────

    /**
     * Writes [pictureBytes] to `<cacheDir>/dsd_art/` using a content-hash filename for
     * deduplication, then returns the resulting `file://` URI string.
     *
     * The cache directory is intentional: Android may reclaim it under storage
     * pressure, but the art will be re-extracted on the next library index pass.
     *
     * @param pictureBytes Raw JPEG or PNG bytes extracted from an APIC frame.
     * @return `"file://<absolute path>"` of the cached art file, or `null` on I/O error.
     */
    private fun saveEmbeddedArtToCache(pictureBytes: ByteArray): String? = runCatching {
        val artDir = File(context.cacheDir, DSD_ART_CACHE_DIR).also { it.mkdirs() }
        // Detect PNG by its magic bytes (89 50 4E 47); everything else is treated as JPEG.
        val isPng = pictureBytes.size >= 4 &&
            pictureBytes[0] == 0x89.toByte() && pictureBytes[1] == 0x50.toByte() &&
            pictureBytes[2] == 0x4E.toByte() && pictureBytes[3] == 0x47.toByte()
        val ext = if (isPng) "png" else "jpg"
        // Fold the byte array into a 32-bit hash for a stable, compact filename.
        val hash = pictureBytes.fold(0L) { acc, b -> (acc * 31L + (b.toLong() and 0xFF)) } and 0xFFFF_FFFFL
        val artFile = File(artDir, "$hash.$ext")
        if (!artFile.exists()) artFile.writeBytes(pictureBytes)
        "file://${artFile.absolutePath}"
    }.getOrNull()

    // ── Container header parsing ──────────────────────────────────────────────

    /**
     * Sample rate and duration recovered from a DSD container header.
     *
     * @property sampleRateHz DSD sampling frequency in Hertz, `0` when unreadable.
     * @property durationMs Track duration in milliseconds, `0` when unreadable.
     */
    private data class DsdHeaderResult(val sampleRateHz: Int, val durationMs: Long)

    /**
     * DSF (Sony DSD Stream File) binary header layout (all integers little-endian):
     * ```
     * Offset  Size  Field
     *  0       4    Magic "DSD "
     *  4       8    DSD chunk size (= 28)
     * 12       8    Total file size
     * 20       8    ID3 metadata offset (0 = no tag)
     * 28       4    "fmt " chunk ID
     * 32       8    fmt chunk size (= 52)
     * 40       4    Format version (= 1)
     * 44       4    Format ID (0 = DSD raw)
     * 48       4    Channel type
     * 52       4    Channel count
     * 56       4    Sampling frequency (Hz)  ← sample rate
     * 60       4    Bits per sample
     * 64       8    Sample count             ← used for duration
     * ```
     */
    private fun parseDsfHeader(bytes: ByteArray): DsdHeaderResult {
        if (bytes.size < 80) return DsdHeaderResult(0, 0)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val sampleRate = buf.getInt(56)
        val sampleCount = buf.getLong(64)
        val duration = if (sampleRate > 0) (sampleCount * 1_000L) / sampleRate else 0L
        return DsdHeaderResult(sampleRate, duration)
    }

    /**
     * DFF (DSDIFF) parsing walks the big-endian chunk tree to locate the PROP/FSXX
     * sub-chunk (sample rate) and the DSD chunk (sample count).
     */
    private fun parseDffHeader(source: SeekableDocumentSource): DsdHeaderResult {
        return runCatching {
            source.seek(12) // skip FRM8 header (4 ID + 8 size + 4 form-type)
            var sampleRate = 0
            var dsdSampleCount = 0L

            while (source.position < source.length() - 12) {
                val chunkId = readChunkId(source)
                val chunkSize = readInt64BE(source)

                when (chunkId) {
                    "PROP" -> {
                        source.seek(source.position + 4) // skip "SND " form type
                        val propEnd = source.position + chunkSize - 4
                        while (source.position < propEnd - 12) {
                            val subId = readChunkId(source)
                            val subSize = readInt64BE(source)
                            if (subId == "FSXX" || subId == "FS  ") {
                                sampleRate = readInt32BE(source)
                                break
                            } else {
                                source.seek(source.position + subSize + (subSize and 1L))
                            }
                        }
                        source.seek(propEnd)
                    }
                    "DSD " -> {
                        dsdSampleCount = readInt64BE(source)
                        source.seek(source.position + chunkSize - 8 + (chunkSize and 1L))
                    }
                    else -> {
                        val skip = chunkSize + (chunkSize and 1L)
                        if (skip <= 0 || source.position + skip > source.length()) break
                        source.seek(source.position + skip)
                    }
                }
                if (sampleRate > 0 && dsdSampleCount > 0L) break
            }

            val duration = if (sampleRate > 0 && dsdSampleCount > 0L) {
                (dsdSampleCount * 1_000L) / sampleRate
            } else 0L

            DsdHeaderResult(sampleRate, duration)
        }.getOrDefault(DsdHeaderResult(0, 0))
    }

    // ── Low-level I/O helpers ─────────────────────────────────────────────────

    private fun readHeaderBytes(source: SeekableDocumentSource): ByteArray? = runCatching {
        val size = minOf(HEADER_READ_SIZE.toLong(), source.length()).toInt()
        source.seek(0)
        ByteArray(size).also { source.readFully(it) }
    }.getOrNull()

    private fun readChunkId(source: SeekableDocumentSource): String {
        val buf = ByteArray(4)
        source.readFully(buf)
        return String(buf, Charsets.US_ASCII)
    }

    private fun readInt32BE(source: SeekableDocumentSource): Int {
        val buf = ByteArray(4)
        source.readFully(buf)
        return ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN).int
    }

    private fun readInt64BE(source: SeekableDocumentSource): Long {
        val buf = ByteArray(8)
        source.readFully(buf)
        return ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN).long
    }

    private fun isDsfMagic(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 'D'.code.toByte() && bytes[1] == 'S'.code.toByte() &&
            bytes[2] == 'D'.code.toByte() && bytes[3] == ' '.code.toByte()

    private fun isDffMagic(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 'F'.code.toByte() && bytes[1] == 'R'.code.toByte() &&
            bytes[2] == 'M'.code.toByte() && bytes[3] == '8'.code.toByte()

    companion object {
        private val DSD_EXTENSIONS = setOf("dsf", "dff")
        private const val MIME_DSD = "audio/dsd"
        private const val MIN_DURATION_MS = 30_000L
        private const val HEADER_READ_SIZE = 128

        /** Display fallbacks matching the sentinel clean-up applied to MediaStore rows. */
        private const val UNKNOWN_ARTIST = "Unknown Artist"
        private const val UNKNOWN_ALBUM = "Unknown Album"

        /** Directory name of the Android app-data tree, never walked for user media. */
        private const val ANDROID_DATA_DIR = "Android"

        /** Sub-directory name inside [Context.getCacheDir] used for cached DSD artwork. */
        private const val DSD_ART_CACHE_DIR = "dsd_art"

        /**
         * Maximum byte count accepted for a single ID3v2 frame.
         *
         * Any frame claiming to be larger than this is treated as a corrupt tag
         * rather than a legitimately large embedded picture.
         */
        private const val MAX_FRAME_BYTES = 30_000_000 // 30 MB

        /** Columns read for every child document while walking a granted tree. */
        private val CHILD_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}
