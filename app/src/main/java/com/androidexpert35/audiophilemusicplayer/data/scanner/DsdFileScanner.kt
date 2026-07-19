package com.androidexpert35.audiophilemusicplayer.data.scanner

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Environment
import android.os.storage.StorageManager
import com.androidexpert35.audiophilemusicplayer.data.scanner.DsdFileScanner.Companion.SCAN_SUBDIRS
import com.androidexpert35.audiophilemusicplayer.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supplementary scanner that discovers DSD audio files (`.dsf`, `.dff`) from the
 * device filesystem.
 *
 * **Why a separate scanner is needed:**
 * Android's system media scanner does not register DSD formats in
 * `MediaStore.Audio.Media` because the OS has no built-in DSD decoder and therefore
 * no MIME type entry for these containers. DSD files placed in the Music folder are
 * completely invisible to any `ContentResolver` query against MediaStore — they are
 * never inserted as rows in the first place.
 *
 * This scanner compensates by:
 * 1. Enumerating ALL mounted storage volumes via [StorageManager.getStorageVolumes] so
 *    SD cards are discovered reliably regardless of their mount path.
 * 2. Walking several standard audio directories on each volume (`Music`, `Downloads`,
 *    `Documents`, `Audiobooks`, and the volume root itself).
 * 3. Attempting [MediaMetadataRetriever] for tag-based metadata first.
 * 4. **Falling back to direct ID3v2 binary parsing** for DSF files when MMR fails —
 *    necessary on most Android OEMs where the platform codec framework has no DSD
 *    decoder and MMR returns null for every metadata key despite opening the file.
 * 5. Extracting embedded album art (APIC frame) and caching it as a `file://` URI in
 *    the app's cache directory so Coil can load it without MediaStore involvement.
 * 6. Parsing the binary DSF / DFF header directly to recover duration when MMR fails.
 * 7. Producing [ScannedAudioFile] records with `file://` content URIs that Media3 /
 *    ExoPlayer can open directly for playback.
 *
 * Stable track IDs are generated from each file's absolute path hash, using a
 * negative Long range so they cannot collide with positive MediaStore IDs.
 *
 * @property context Application context used to enumerate storage volumes and write
 *   cached artwork to [Context.getCacheDir].
 * @property ioDispatcher Background dispatcher for all blocking I/O operations.
 */
@Singleton
class DsdFileScanner @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {

    /**
     * Scans all accessible storage volumes for `.dsf` and `.dff` files.
     *
     * Uses [StorageManager.getStorageVolumes] to enumerate every mounted volume so
     * external SD cards are found at their correct root path. On each volume the
     * scanner walks [SCAN_SUBDIRS] plus the volume root itself (one level deep only
     * for the root to avoid traversing system folders).
     *
     * @return List of [ScannedAudioFile] records representing discovered DSD tracks,
     *   sorted by title. Returns an empty list when no DSD files are found.
     */
    suspend fun scanDsdFiles(): List<ScannedAudioFile> = withContext(ioDispatcher) {
        val results = mutableListOf<ScannedAudioFile>()
        val visited = mutableSetOf<String>()

        val volumeRoots = resolveVolumeRoots()

        for (root in volumeRoots) {
            // 1. Walk each standard audio sub-directory recursively.
            for (subdir in SCAN_SUBDIRS) {
                val dir = File(root, subdir)
                if (dir.exists() && dir.isDirectory) {
                    walkForDsd(dir, recursive = true, visited = visited, results = results)
                }
            }

            // 2. Scan the volume root itself one level deep (non-recursive) so files
            //    placed directly in the root or in a single custom folder are found
            //    without risking traversal of Android system directories.
            walkForDsd(root, recursive = false, visited = visited, results = results)
        }

        results.sortedBy { it.title }
    }

    /**
     * Resolves the root [File] for every mounted storage volume.
     *
     * Prefers [StorageManager.getStorageVolumes] (API 24+, enhanced in API 30) for
     * accurate volume roots, then falls back to [Environment.getExternalStorageDirectory]
     * for the primary volume when the storage manager returns nothing useful.
     */
    private fun resolveVolumeRoots(): List<File> {
        val roots = mutableListOf<File>()
        val storageManager = context.getSystemService(StorageManager::class.java)

        storageManager?.storageVolumes?.forEach { volume ->
            // StorageVolume.directory is available on API 30+ (minSdk = 33).
            volume.directory
                ?.takeIf { it.exists() && it.canRead() }
                ?.let { roots += it }
        }

        // Fallback: if StorageManager returns nothing, use the primary external storage root.
        if (roots.isEmpty()) {
            Environment.getExternalStorageDirectory()
                ?.takeIf { it.exists() && it.canRead() }
                ?.let { roots += it }
        }

        return roots.distinctBy { file -> runCatching { file.canonicalPath }.getOrElse { file.absolutePath } }
    }

    /**
     * Walks [dir] for DSD files, optionally recurring into sub-directories.
     *
     * Each file is processed in isolation — an exception in [scanSingleFile] is caught
     * so one corrupt file does not abort the walk. Directories starting with `.`
     * (hidden) or named `Android` (system data tree) are always skipped.
     *
     * @param dir Directory to walk.
     * @param recursive Whether to descend into sub-directories.
     * @param visited Canonical paths already processed; prevents duplicate entries when
     *   the same volume is reachable via multiple symlinks.
     * @param results Accumulator for discovered [ScannedAudioFile] records.
     */
    private fun walkForDsd(
        dir: File,
        recursive: Boolean,
        visited: MutableSet<String>,
        results: MutableList<ScannedAudioFile>,
    ) {
        val sequence = if (recursive) {
            dir.walkTopDown().onEnter { sub ->
                // Skip hidden directories and the Android data tree.
                !sub.name.startsWith(".") && sub.name != "Android"
            }
        } else {
            // Non-recursive: only immediate children of dir.
            (dir.listFiles()?.asSequence() ?: emptySequence())
        }

        sequence
            .filter { file -> file.isFile && file.extension.lowercase() in DSD_EXTENSIONS }
            .forEach { file ->
                val canonical = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
                if (visited.add(canonical)) {
                    scanSingleFile(file)?.let { results += it }
                }
            }
    }

    /**
     * Extracts metadata for a single DSD file and converts it to a [ScannedAudioFile].
     *
     * **Metadata resolution order:**
     * 1. [MediaMetadataRetriever] — fast and sufficient on Android versions /
     *    OEMs that recognise DSF in the platform codec framework.
     * 2. Direct ID3v2 binary parsing (DSF only) — used when MMR returns blank
     *    values or throws. Seeks to the ID3 offset recorded in the DSF chunk
     *    header and reads TIT2 / TPE1 / TALB / TRCK / TDRC frames directly.
     * 3. Binary DSD / DFF header parsing for duration when both tag sources fail.
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
     * @param file Candidate DSD audio file from the filesystem walk.
     * @return Populated [ScannedAudioFile], or `null` if the file is unreadable or has
     *   a duration below the 30-second threshold used for MediaStore tracks.
     */
    private fun scanSingleFile(file: File): ScannedAudioFile? = runCatching {
        val absolutePath = file.absolutePath
        // Derive a stable, collision-free ID from the absolute path. MediaStore IDs are
        // always positive; negating the hash pushes these IDs into the negative range.
        val stableId = -(absolutePath.hashCode().toLong().and(0x7FFF_FFFFL) + 1L)

        var title = file.nameWithoutExtension
        var artist = "Unknown Artist"
        var album = "Unknown Album"
        var durationMs = 0L
        var trackNumber = 0
        var year = 0
        var embeddedPicture: ByteArray? = null

        // ── Phase 1: tag-based metadata via MediaMetadataRetriever ───────────
        // MMR works for DSF on some Android versions (especially stock AOSP) but
        // fails silently — returning nulls instead of throwing — on many OEM
        // firmware builds that lack a DSD codec entry in the platform stack.
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(absolutePath)
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
            embeddedPicture = try { mmr.getEmbeddedPicture() } catch (_: Exception) { null }
        } catch (_: Exception) {
            // MMR silently fails on some DFF files; header parsing below is the fallback.
        } finally {
            runCatching { mmr.release() }
        }

        // ── Phase 1b: Direct ID3v2 binary parsing (DSF only) ────────────────
        // DSF files embed an ID3v2 tag whose file offset is stored at bytes 20–27
        // of the DSD chunk header (little-endian int64). When MMR returns blank
        // values — which happens on most Samsung-derived Android and many OEMs —
        // we parse TIT2, TPE1, TALB, TRCK, TDRC, and APIC frames directly from the
        // raw bytes. This path is reliable on every Android version because it
        // requires only RandomAccessFile, not a platform codec decoder.
        if (file.extension.lowercase() == "dsf") {
            val id3Offset = readDsfId3Offset(file)
            if (id3Offset > 0L) {
                val id3 = parseId3v2Tag(file, id3Offset)
                // Only promote direct-parsed values when MMR left them as defaults.
                if (title == file.nameWithoutExtension) id3.title?.let { title = it }
                if (artist == "Unknown Artist") id3.artist?.let { artist = it }
                if (album == "Unknown Album") id3.album?.let { album = it }
                if (trackNumber == 0) id3.trackNumber.takeIf { it > 0 }?.let { trackNumber = it }
                if (year == 0) id3.year.takeIf { it > 0 }?.let { year = it }
                if (embeddedPicture == null) embeddedPicture = id3.pictureBytes
            }
        }

        // ── Phase 2: binary header parsing for duration fallback ─────────────
        if (durationMs <= 0L) {
            val headerData = readHeaderBytes(file)
            if (headerData != null) {
                durationMs = when {
                    isDsfMagic(headerData) -> parseDsfHeader(headerData).durationMs
                    isDffMagic(headerData) -> parseDffHeader(file).durationMs
                    else -> 0L
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
            contentUri = "file://$absolutePath",
            filePath = absolutePath,
            trackNumber = trackNumber,
            discNumber = 1,
            mimeType = MIME_DSD,
            fileSizeBytes = file.length(),
            dateAdded = file.lastModified() / 1_000L,
            year = year,
            artUri = artUri,
            // DSD sample rates: DSD64 = 2 822 400 Hz, DSD128 = 5 644 800, DSD256 = 11 289 600.
            // Read from the binary header since MMR does not expose DSD sample rate.
            sampleRateHz = readDsdSampleRate(file),
            bitDepth = 1, // DSD is inherently 1-bit; mark explicitly for display purposes.
        )
    }.getOrNull()

    // ── DSD sample-rate extraction ────────────────────────────────────────────

    /**
     * Reads the sample rate directly from the DSF or DFF file header.
     * Returns 0 if the file is not a recognised DSD container or is unreadable.
     */
    private fun readDsdSampleRate(file: File): Int {
        val headerData = readHeaderBytes(file) ?: return 0
        return when {
            isDsfMagic(headerData) -> parseDsfHeader(headerData).sampleRateHz
            isDffMagic(headerData) -> parseDffHeader(file).sampleRateHz
            else -> 0
        }
    }

    // ── DSF ID3v2 offset extraction ───────────────────────────────────────────

    /**
     * Reads the ID3v2 tag offset stored at bytes 20–27 (little-endian `int64`) of
     * the DSF chunk header. Returns −1 if the file cannot be read or has no tag.
     */
    private fun readDsfId3Offset(file: File): Long = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 28) return@runCatching -1L
            raf.seek(20)
            val buf = ByteArray(8)
            raf.readFully(buf)
            ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).long
        }
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
     * @property pictureBytes Raw JPEG/PNG bytes from the first APIC frame, or `null`.
     */
    private class Id3TagResult(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val trackNumber: Int = 0,
        val year: Int = 0,
        val pictureBytes: ByteArray? = null,
    )

    /**
     * Parses the ID3v2 tag at [offset] inside [file], extracting text frames and
     * the first APIC (attached picture) frame.
     *
     * Supports ID3v2.3 and v2.4. Frame sizes are plain big-endian int32 in v2.3
     * and syncsafe int32 in v2.4. Text encoding is honoured for both ASCII/Latin-1
     * and UTF-8/UTF-16.
     *
     * @param file DSF file containing the tag.
     * @param offset Byte offset of the `ID3` magic within the file.
     * @return Parsed [Id3TagResult]; all fields are null/zero on parse failure.
     */
    private fun parseId3v2Tag(file: File, offset: Long): Id3TagResult = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)

            // Verify ID3v2 magic.
            val magic = ByteArray(3).also { raf.readFully(it) }
            if (String(magic, Charsets.US_ASCII) != "ID3") return@runCatching Id3TagResult()

            val versionMajor = raf.read()  // 3 = v2.3, 4 = v2.4
            raf.read()                      // revision (ignored)
            val flags = raf.read()
            val hasExtendedHeader = (flags and 0x40) != 0

            // Tag size is always a syncsafe int32 (7-bit bytes).
            val tagSize = readSyncsafeInt(raf)
            val tagEnd = offset + 10L + tagSize

            // Skip the optional extended header.
            if (hasExtendedHeader) {
                val extHeaderSize = if (versionMajor == 4) readSyncsafeInt(raf) else readInt32BE(raf)
                // extHeaderSize includes its own 4-byte field; skip the remaining content.
                val remaining = (extHeaderSize - 4).coerceAtLeast(0)
                if (remaining > 0) raf.skipBytes(remaining)
            }

            var title: String? = null
            var artist: String? = null
            var album: String? = null
            var trackNumber = 0
            var year = 0
            var pictureBytes: ByteArray? = null

            // Iterate frames until padding (all-zero ID) or tag boundary.
            while (raf.filePointer < tagEnd - 10 && raf.filePointer < raf.length() - 10) {
                val frameIdBytes = ByteArray(4).also { raf.readFully(it) }
                val frameId = String(frameIdBytes, Charsets.US_ASCII)

                // All-zero ID bytes signal the start of tag padding — stop here.
                if (frameIdBytes.all { it == 0.toByte() }) break

                val frameSize = if (versionMajor == 4) readSyncsafeInt(raf) else readInt32BE(raf)
                raf.read()  // frame flags byte 1 (ignored)
                raf.read()  // frame flags byte 2 (ignored)

                // Guard against absurd frame sizes (corrupt tag or seek to wrong offset).
                if (frameSize <= 0 || frameSize > MAX_FRAME_BYTES) break

                val frameData = ByteArray(frameSize).also { raf.readFully(it) }

                when (frameId) {
                    "TIT2" -> if (title == null) title = decodeId3TextFrame(frameData)
                    "TPE1" -> if (artist == null) artist = decodeId3TextFrame(frameData)
                    "TALB" -> if (album == null) album = decodeId3TextFrame(frameData)
                    "TRCK" -> if (trackNumber == 0)
                        trackNumber = decodeId3TextFrame(frameData)
                            ?.substringBefore('/')?.toIntOrNull() ?: 0
                    "TDRC", "TYER" -> if (year == 0)
                        year = decodeId3TextFrame(frameData)?.take(4)?.toIntOrNull() ?: 0
                    "APIC" -> if (pictureBytes == null) pictureBytes = extractApicBytes(frameData)
                }
            }

            Id3TagResult(title, artist, album, trackNumber, year, pictureBytes)
        }
    }.getOrDefault(Id3TagResult())

    /**
     * Reads a 4-byte syncsafe integer from [raf].
     *
     * ID3v2 syncsafe integers use only the lower 7 bits of each byte, giving a
     * 28-bit effective range. Used for the tag header size and all v2.4 frame sizes.
     */
    private fun readSyncsafeInt(raf: RandomAccessFile): Int {
        val b = ByteArray(4).also { raf.readFully(it) }
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

    // ── DSF header parsing ────────────────────────────────────────────────────

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
    private data class DsfHeaderResult(val sampleRateHz: Int, val durationMs: Long)

    private fun parseDsfHeader(bytes: ByteArray): DsfHeaderResult {
        if (bytes.size < 80) return DsfHeaderResult(0, 0)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val sampleRate = buf.getInt(56)
        val sampleCount = buf.getLong(64)
        val duration = if (sampleRate > 0) (sampleCount * 1_000L) / sampleRate else 0L
        return DsfHeaderResult(sampleRate, duration)
    }

    // ── DFF header parsing ────────────────────────────────────────────────────

    private data class DffHeaderResult(val sampleRateHz: Int, val durationMs: Long)

    /**
     * DFF (DSDIFF) parsing walks the big-endian chunk tree to locate the PROP/FSXX
     * sub-chunk (sample rate) and the DSD chunk (sample count).
     */
    private fun parseDffHeader(file: File): DffHeaderResult {
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(12) // skip FRM8 header (4 ID + 8 size + 4 form-type)
                var sampleRate = 0
                var dsdSampleCount = 0L

                while (raf.filePointer < raf.length() - 12) {
                    val chunkId = readChunkId(raf)
                    val chunkSize = readInt64BE(raf)

                    when (chunkId) {
                        "PROP" -> {
                            raf.seek(raf.filePointer + 4) // skip "SND " form type
                            val propEnd = raf.filePointer + chunkSize - 4
                            while (raf.filePointer < propEnd - 12) {
                                val subId = readChunkId(raf)
                                val subSize = readInt64BE(raf)
                                if (subId == "FSXX" || subId == "FS  ") {
                                    sampleRate = readInt32BE(raf)
                                    break
                                } else {
                                    raf.seek(raf.filePointer + subSize + (subSize and 1L))
                                }
                            }
                            raf.seek(propEnd)
                        }
                        "DSD " -> {
                            dsdSampleCount = readInt64BE(raf)
                            raf.seek(raf.filePointer + chunkSize - 8 + (chunkSize and 1L))
                        }
                        else -> {
                            val skip = chunkSize + (chunkSize and 1L)
                            if (skip <= 0 || raf.filePointer + skip > raf.length()) break
                            raf.seek(raf.filePointer + skip)
                        }
                    }
                    if (sampleRate > 0 && dsdSampleCount > 0L) break
                }

                val duration = if (sampleRate > 0 && dsdSampleCount > 0L) {
                    (dsdSampleCount * 1_000L) / sampleRate
                } else 0L

                DffHeaderResult(sampleRate, duration)
            }
        }.getOrDefault(DffHeaderResult(0, 0))
    }

    // ── Low-level I/O helpers ─────────────────────────────────────────────────

    private fun readHeaderBytes(file: File): ByteArray? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val size = minOf(HEADER_READ_SIZE.toLong(), raf.length()).toInt()
            ByteArray(size).also { raf.readFully(it) }
        }
    }.getOrNull()

    private fun readChunkId(raf: RandomAccessFile): String {
        val buf = ByteArray(4)
        raf.readFully(buf)
        return String(buf, Charsets.US_ASCII)
    }

    private fun readInt32BE(raf: RandomAccessFile): Int {
        val buf = ByteArray(4)
        raf.readFully(buf)
        return ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN).int
    }

    private fun readInt64BE(raf: RandomAccessFile): Long {
        val buf = ByteArray(8)
        raf.readFully(buf)
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

        /** Sub-directory name inside [Context.getCacheDir] used for cached DSD artwork. */
        private const val DSD_ART_CACHE_DIR = "dsd_art"

        /**
         * Maximum byte count accepted for a single ID3v2 frame.
         *
         * Any frame claiming to be larger than this is treated as a corrupt tag
         * rather than a legitimately large embedded picture.
         */
        private const val MAX_FRAME_BYTES = 30_000_000 // 30 MB

        /**
         * Sub-directories searched recursively on each storage volume.
         * These cover all standard Android media folders where audiophiles
         * commonly store DSD files.
         */
        private val SCAN_SUBDIRS = listOf(
            Environment.DIRECTORY_MUSIC,
            Environment.DIRECTORY_DOWNLOADS,
            Environment.DIRECTORY_DOCUMENTS,
            "Audiobooks",
            "DSD",
            "SACD",
        )
    }
}
