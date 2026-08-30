package com.androidexpert35.audiophilemusicplayer.data.scanner

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.ContentUris
import android.provider.MediaStore
import androidx.core.net.toUri
import com.androidexpert35.audiophilemusicplayer.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Scans the user-granted music folders for local audio files using optimised
 * column projections to minimise memory and I/O overhead.
 *
 * The query is always restricted to the folders the user authorised. Scanning the
 * whole external volume is deliberately not supported: it pulled in every audio file
 * on the device — messenger voice notes above all — and drowned the real library in
 * clips the user never asked to see.
 *
 * After the primary MediaStore cursor pass, any track whose artist or year
 * fields contain MediaStore sentinel / blank values is re-read via
 * [MetadataFallbackReader]. This two-pass strategy ensures that files tagged
 * with **ID3v2.2** — whose 3-character frame identifiers (`TP1`, `TYE`, `TAL`,
 * `PIC`) are not fully recognised by Android's Stagefright ID3 parser — still
 * surface correct metadata in the library rather than showing `<unknown>`.
 *
 * All queries run on the injected [IoDispatcher] to keep the main thread free.
 *
 * @property contentResolver System content resolver for MediaStore queries.
 * @property fallbackReader Secondary metadata reader used for ID3v2.2 / mixed-tag files.
 * @property audioContentKeyReader Samples each file's audio payload to produce the stable
 *   content key an analysis result is cached against.
 * @property ioDispatcher Background dispatcher for blocking cursor operations.
 */
@Singleton
class MediaStoreScanner @Inject constructor(
    private val contentResolver: ContentResolver,
    private val fallbackReader: MetadataFallbackReader,
    private val audioContentKeyReader: AudioContentKeyReader,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher
) {

    /**
     * Performs a MediaStore scan to discover every audio file needed for initial indexing,
     * with an automatic ID3v2.2 fallback pass for any track whose metadata was not fully
     * parsed by Android's Stagefright indexer.
     *
     * **Two-pass strategy**
     * 1. A single MediaStore cursor read collects all fields MediaStore provides.
     * 2. Any track flagged by [MetadataFallbackReader.needsFallback] (artist is the
     *    `<unknown>` sentinel or year is missing) is re-read directly via
     *    [MediaMetadataRetriever] to recover the ID3v2.2 frames that Stagefright missed.
     * 3. If the album-art URI is still absent after the text-metadata pass, embedded
     *    picture bytes are extracted from the file and written to the app's cache dir,
     *    producing a `file://` URI that Coil can load normally.
     * 4. Every file — not only the ones needing a fallback — is sampled by
     *    [AudioContentKeyReader] for its stable audio-content key.
     *
     * The fallback is deliberately surgical — it runs only for affected files, so
     * the total I/O overhead scales with the number of problematic tracks, not the
     * entire library.
     *
     * @param folders Locations the user authorised as music folders. An empty list yields
     *   an empty result — the scan never falls back to the whole device.
     * @param onProgress Invoked once per scanned file as the ID3v2.2 fallback pass walks
     *   the result set, with the number of files processed so far, the total file count,
     *   and the path of the file just processed. This is the dominant per-file I/O cost of
     *   a scan (raw MediaStore cursor rows are already materialised in memory), so it is the
     *   source of truth for onboarding's step-by-step progress bar.
     * @return List of raw [ScannedAudioFile] records ordered by title, with fields
     *   recovered from ID3v2.2 tags wherever MediaStore missed them.
     */
    // SAMPLERATE and BITS_PER_SAMPLE are T Extensions SDK symbols; the underlying columns
    // exist on all Android 12+ (API 31+) devices and minSdk = 33 guarantees availability.
    @SuppressLint("NewApi")
    suspend fun scanAudioFilesForIndexing(
        folders: List<MusicFolderScope>,
        onProgress: (processed: Int, total: Int, filePath: String) -> Unit = { _, _, _ -> }
    ): List<ScannedAudioFile> = withContext(ioDispatcher) {
        if (folders.isEmpty()) return@withContext emptyList()

        val files = mutableListOf<ScannedAudioFile>()

        contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            MediaStoreColumns.TRACK_PROJECTION,
            MediaStoreColumns.trackSelectionForFolders(folders),
            MediaStoreColumns.trackSelectionArgsForFolders(folders),
            MediaStoreColumns.TRACK_SORT_ORDER
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID))
                // Preserve MediaStore's raw value (including the "<unknown>" sentinel) so
                // needsFallback() can detect ID3v2.2-affected files precisely.
                val title = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)) ?: "Unknown"
                val artistName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)) ?: ""
                val rawArtistId = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST_ID))
                val artistId = rawArtistId.takeIf { it > 0L } ?: artistName.hashCode().toLong()
                val albumTitle = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)) ?: ""
                val albumId = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID))
                val durationMs = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION))
                val dataPath = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)).orEmpty()
                val displayName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)).orEmpty()
                val relativePath = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)).orEmpty()
                val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE))
                val dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED))
                val encodedTrackNumber = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK))
                val discNumber = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISC_NUMBER))
                val mimeType = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)).orEmpty()
                val year = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR))
                val genre = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.GENRE))
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                val composer = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.AudioColumns.COMPOSER))
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                // SAMPLERATE and BITS_PER_SAMPLE are available from API 31 (minSdk = 33).
                val sampleRateHz = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SAMPLERATE))
                val bitDepth = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.BITS_PER_SAMPLE))

                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    id
                ).toString()

                val filePath = dataPath.ifBlank {
                    buildString {
                        append(relativePath)
                        append(displayName)
                    }.ifBlank { displayName }
                }

                val albumArtUri = if (albumId > 0L) {
                    ContentUris.withAppendedId(
                        "content://media/external/audio/albumart".toUri(),
                        albumId
                    ).toString()
                } else {
                    null
                }

                files += ScannedAudioFile(
                    id = id,
                    title = title,
                    artistId = artistId,
                    artistName = artistName,
                    albumId = albumId,
                    albumTitle = albumTitle,
                    durationMs = durationMs,
                    contentUri = contentUri,
                    filePath = filePath,
                    trackNumber = encodedTrackNumber % 1000,
                    discNumber = if (discNumber > 0) discNumber else (encodedTrackNumber / 1000).coerceAtLeast(1),
                    mimeType = mimeType,
                    fileSizeBytes = size,
                    dateAdded = dateAdded,
                    year = year,
                    artUri = albumArtUri,
                    sampleRateHz = sampleRateHz,
                    bitDepth = bitDepth,
                    genre = genre,
                    composer = composer,
                )
            }
        }

        // Second pass — derive the audio-content key and repair incomplete MediaStore
        // metadata, including FLAC DATE tags exposed as a missing YEAR value. The
        // metadata-repair overhead is proportional to the affected tracks, not the whole
        // library.
        enrichScanResults(files, onProgress)
    }

    /**
     * Iterates the scan results, derives every entry's audio-content key, and enriches any
     * entry flagged by [MetadataFallbackReader] with metadata read directly via
     * [MediaMetadataRetriever], then resolves embedded album art into a cache-backed
     * `file://` URI when the MediaStore art cache is absent.
     *
     * The content key is read for every file because it is what a later per-track analysis
     * is cached against; a file that cannot be opened keeps an empty key and stays in the
     * results, since an unanalysable track is still a playable one.
     *
     * @param files Mutable list produced by the MediaStore cursor pass.
     * @param onProgress Invoked after each file is visited (whether or not it needed the
     *   fallback read) so callers can render step-by-step scan progress.
     * @return The same list with every entry carrying its content key and ID3v2.2-affected
     *   entries replaced by enriched copies.
     */
    private fun enrichScanResults(
        files: MutableList<ScannedAudioFile>,
        onProgress: (processed: Int, total: Int, filePath: String) -> Unit
    ): List<ScannedAudioFile> {
        // Track album IDs for which we already have or attempted embedded-art extraction
        // so we only write one cache file per album rather than per track.
        val enrichedAlbumArtIds = mutableSetOf<Long>()
        val total = files.size

        for (i in files.indices) {
            val file = files[i]
            val audioKey = audioContentKeyReader.read(file.contentUri.toUri(), file.fileSizeBytes)

            if (!fallbackReader.needsFallback(file)) {
                files[i] = file.copy(audioKey = audioKey)
                onProgress(i + 1, total, file.filePath)
                continue
            }

            // Recover missing text metadata (artist, album, year, title) from the raw file.
            val enriched = fallbackReader.readTextMetadata(file)

            // Attempt embedded-art extraction when the MediaStore album-art cache
            // likely has no entry for this track (same root cause: ID3v2.2 PIC frame).
            val artUri = if (enriched.artUri == null && enriched.albumId > 0L &&
                enrichedAlbumArtIds.add(enriched.albumId)
            ) {
                fallbackReader.readEmbeddedArtUri(enriched) ?: enriched.artUri
            } else {
                enriched.artUri
            }

            // Replace the original entry with the enriched copy.
            files[i] = enriched.copy(artUri = artUri, audioKey = audioKey)
            onProgress(i + 1, total, file.filePath)
        }

        // Final sentinel clean-up: replace any remaining MediaStore "<unknown>" or blank
        // values with human-friendly fallback strings for tracks where even the retriever
        // could not recover the field (e.g. truly untagged files).
        return files.map { f ->
            val needsSentinelClean = f.artistName == "<unknown>" || f.artistName.isBlank() ||
                f.albumTitle == "<unknown>" || f.albumTitle.isBlank()
            if (needsSentinelClean) {
                f.copy(
                    artistName = f.artistName.takeUnless { it == "<unknown>" || it.isBlank() } ?: "Unknown Artist",
                    albumTitle = f.albumTitle.takeUnless { it == "<unknown>" || it.isBlank() } ?: "Unknown Album",
                )
            } else {
                f
            }
        }
    }
}
