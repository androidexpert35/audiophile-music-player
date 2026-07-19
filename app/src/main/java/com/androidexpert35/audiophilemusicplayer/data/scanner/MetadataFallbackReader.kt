package com.androidexpert35.audiophilemusicplayer.data.scanner

import android.content.Context
import android.media.MediaMetadataRetriever
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides a best-effort metadata fallback for audio files whose tags were not
 * fully parsed by Android's MediaStore indexer.
 *
 * **Why this is needed**
 *
 * Android's MediaStore uses the Stagefright media framework to index MP3 metadata.
 * Stagefright reliably handles ID3v2.3 and ID3v2.4 frames but has well-documented
 * incomplete support for **ID3v2.2**, which uses entirely different 3-character frame
 * identifiers:
 *
 * | Metadata   | ID3v2.2 frame | ID3v2.3/v2.4 frame |
 * |------------|---------------|--------------------|
 * | Artist     | `TP1`         | `TPE1`             |
 * | Album      | `TAL`         | `TALB`             |
 * | Year       | `TYE`         | `TYER` / `TDRC`   |
 * | Title      | `TT2`         | `TIT2`             |
 * | Album Art  | `PIC`         | `APIC`             |
 *
 * When MediaStore encounters those 3-char frames — or a file that mixes v2.2 and
 * v2.3 tags in the same ID3 header — it returns empty strings or zero values and
 * writes no album-art entry to the system album-art cache. The result is `<unknown>`
 * artist, missing year, and a broken album-art URI for otherwise well-tagged files.
 *
 * This reader calls [MediaMetadataRetriever] directly on the file's content URI at
 * scan time, bypassing the cached database entirely. It is invoked **only** for tracks
 * where MediaStore returned sentinel/blank values (artist `<unknown>` or year `0`),
 * so the performance cost is confined to genuinely affected files.
 *
 * For album art, [readEmbeddedArtUri] extracts the raw embedded picture bytes via
 * [MediaMetadataRetriever.getEmbeddedPicture] and writes them to a private JPEG file
 * in the app's cache directory. The returned `file://` URI can be stored in Room
 * and loaded by Coil as a first-class local source.
 *
 * @property context Application context used to open content URIs and resolve the cache directory.
 */
@Singleton
class MetadataFallbackReader @Inject constructor(
    @param:ApplicationContext private val context: Context
) {

    /**
     * Sentinel string that Android's MediaStore stores for an unknown artist.
     * Matching against this constant, rather than our own "Unknown Artist" default,
     * avoids triggering the fallback on files that were legitimately untagged by the user.
     */
    private val mediaStoreSentinelArtist = "<unknown>"

    /**
     * Returns `true` when the given [ScannedAudioFile] exhibits symptoms of an
     * incomplete ID3v2.2 tag read by MediaStore. Specifically this is the case when:
     * - The artist is the raw MediaStore sentinel `<unknown>` (blank/null is already
     *   guarded in the caller), **or**
     * - The year is missing (`0`). This is common for Vorbis-commented FLAC files:
     *   MediaStore can retain their artist and album while failing to surface the
     *   `DATE` tag in its `YEAR` column.
     *
     * @param file The scan result to evaluate.
     * @return `true` if the fallback reader should be consulted for this track.
     */
    fun needsFallback(file: ScannedAudioFile): Boolean =
        file.artistName == mediaStoreSentinelArtist ||
            file.artistName.isBlank() ||
            file.year == 0

    /**
     * Fills in missing text metadata for a [ScannedAudioFile] using
     * [MediaMetadataRetriever] as a secondary read path.
     *
     * The retriever is opened, queried for title/artist/album/year, then
     * immediately released — no long-lived handles are kept. Fields already
     * populated by MediaStore are left untouched.
     *
     * @param file The [ScannedAudioFile] whose text fields should be enriched.
     * @return A copy of [file] with blank/sentinel fields replaced by
     *   retriever-sourced values, or the original [file] if the retriever fails.
     */
    fun readTextMetadata(file: ScannedAudioFile): ScannedAudioFile {
        val retriever = MediaMetadataRetriever()
        return try {
            val uri = file.contentUri.toUri()
            retriever.setDataSource(context, uri)

            val artist = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() && it != mediaStoreSentinelArtist }

            val album = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?.takeIf { it.isNotBlank() && it != mediaStoreSentinelArtist }

            val title = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() }

            val retrieverYear = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
                .let(FlacVorbisCommentParser::parseYear)
            val year = retrieverYear ?: readFlacVorbisYear(file)

            // Only copy() when at least one field was recovered to avoid unnecessary allocation.
            if (artist == null && album == null && title == null && year == null) {
                file
            } else {
                file.copy(
                    title = title ?: file.title,
                    artistName = artist ?: file.artistName,
                    albumTitle = album ?: file.albumTitle,
                    year = year ?: file.year,
                )
            }
        } catch (_: Exception) {
            // If the retriever fails (corrupted file, permission revoked mid-scan, etc.),
            // silently return the original scan result so the library scan is not aborted.
            file
        } finally {
            // Always release the retriever to free the native file handle immediately.
            retriever.release()
        }
    }

    /**
     * Extracts the embedded album-art picture from a track and writes it to a JPEG
     * file in the app's private `album_art` cache directory.
     *
     * **When to call this**: only after [readTextMetadata] recovered useful metadata
     * (or when the existing [ScannedAudioFile.artUri] is `null`), because the most
     * common scenario for a missing album art URI is an ID3v2.2-tagged file whose
     * `PIC` frame was never ingested into the MediaStore album-art cache.
     *
     * The file is named `album_<albumId>.jpg` so multiple tracks on the same album
     * reuse one cached file, matching MediaStore's own album-scoped art behaviour.
     * If the file already exists on disk the extraction is skipped and the existing
     * `file://` URI is returned immediately, keeping re-scan overhead negligible.
     *
     * @param file The [ScannedAudioFile] from which to extract embedded picture data.
     * @return A `file://` URI [String] pointing to the extracted JPEG, or `null` if
     *   no embedded picture was found or the extraction failed.
     */
    fun readEmbeddedArtUri(file: ScannedAudioFile): String? {
        // Reuse a previously written cache file — do not re-extract on every scan.
        val cacheFile = embeddedArtCacheFile(file.albumId)
        if (cacheFile.exists()) return cacheFile.toURI().toString()

        val retriever = MediaMetadataRetriever()
        return try {
            val uri = file.contentUri.toUri()
            retriever.setDataSource(context, uri)

            val bytes = retriever.embeddedPicture ?: return null

            // Ensure the cache directory exists before writing.
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeBytes(bytes)
            cacheFile.toURI().toString()
        } catch (_: Exception) {
            // Partially-written files could confuse future reads; remove them.
            runCatching { cacheFile.delete() }
            null
        } finally {
            retriever.release()
        }
    }

    /**
     * Returns the private cache [File] path used to store extracted album art for
     * the given [albumId].
     *
     * @param albumId MediaStore album identifier used as the filename discriminator.
     */
    private fun embeddedArtCacheFile(albumId: Long): File =
        File(context.cacheDir, "album_art/album_$albumId.jpg")

    /** Reads a FLAC's Vorbis DATE/YEAR tag when Android's metadata APIs omit it. */
    private fun readFlacVorbisYear(file: ScannedAudioFile): Int? {
        val isFlac = file.mimeType.equals("audio/flac", ignoreCase = true) ||
            file.filePath.endsWith(".flac", ignoreCase = true)
        if (!isFlac) return null

        return context.contentResolver
            .openInputStream(file.contentUri.toUri())
            ?.use(FlacVorbisCommentParser::readYear)
    }
}
