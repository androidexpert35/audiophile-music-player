package com.androidexpert35.audiophilemusicplayer.data.scanner

/**
 * Raw metadata snapshot for a single audio file discovered during the initial MediaStore scan.
 *
 * This model stays in the data layer because it still reflects framework-derived indexing data
 * that will later be transformed into Room entities and domain models.
 *
 * @property id Stable MediaStore track identifier.
 * @property title Display track title.
 * @property artistId Stable MediaStore artist identifier, or a generated fallback when absent.
 * @property artistName Artist display name.
 * @property albumId Stable MediaStore album identifier.
 * @property albumTitle Album display name.
 * @property durationMs Track duration in milliseconds.
 * @property contentUri Content URI used for playback.
 * @property filePath Best-effort path-like string used for indexing feedback.
 * @property trackNumber Position inside the album.
 * @property discNumber Disc number for multi-disc albums.
 * @property mimeType Source MIME type reported by MediaStore.
 * @property fileSizeBytes File size in bytes.
 * @property dateAdded Epoch seconds when the file was added to storage.
 * @property year Best-effort release year for album aggregation.
 * @property artUri Content URI for album artwork, when derivable.
 * @property sampleRateHz Sample rate in Hertz read from MediaStore (API 31+), or `0` when unavailable.
 * @property bitDepth Bits per sample read from MediaStore (API 31+), or `0` when unavailable.
 */
data class ScannedAudioFile(
    val id: Long,
    val title: String,
    val artistId: Long,
    val artistName: String,
    val albumId: Long,
    val albumTitle: String,
    val durationMs: Long,
    val contentUri: String,
    val filePath: String,
    val trackNumber: Int,
    val discNumber: Int,
    val mimeType: String,
    val fileSizeBytes: Long,
    val dateAdded: Long,
    val year: Int,
    val artUri: String?,
    val sampleRateHz: Int = 0,
    val bitDepth: Int = 0,
)

