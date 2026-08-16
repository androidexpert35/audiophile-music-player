package com.androidexpert35.audiophilemusicplayer.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room entity representing a fully indexed audio track.
 *
 * Stores the metadata needed for fast in-app browsing and search so the UI can
 * avoid querying MediaStore repeatedly after the initial device scan.
 *
 * @property id Stable MediaStore identifier for the track.
 * @property title Display title surfaced in catalogue and player UI.
 * @property artistId Stable MediaStore identifier for the artist.
 * @property artistName Human-readable artist name.
 * @property albumId Stable MediaStore identifier for the album.
 * @property albumTitle Human-readable album title.
 * @property durationMs Track duration in milliseconds.
 * @property contentUri Content URI used by the playback stack.
 * @property filePath Best-effort filesystem-like path used for indexing feedback.
 * @property trackNumber Position within the album disc.
 * @property discNumber Disc number for multi-disc releases.
 * @property mimeType MediaStore MIME type used to infer codec information.
 * @property fileSizeBytes File size in bytes.
 * @property dateAdded Epoch seconds when the file was added to device storage.
 * @property sampleRateHz Persisted sample rate when known, otherwise `0`.
 * @property bitDepth Persisted bit depth when known, otherwise `0`.
 * @property channelCount Persisted channel count when known.
 * @property isLossless Whether the encoded source is lossless.
 * @property artUri Pre-computed artwork URI. For regular MediaStore tracks this is the
 *   `content://media/external/audio/albumart/<albumId>` URI; for DSD files that are
 *   invisible to MediaStore it is a `file://` URI pointing to the APIC picture cached
 *   in the app's `cacheDir` by [com.androidexpert35.audiophilemusicplayer.data.scanner.DsdFileScanner].
 *   `null` when no artwork could be extracted at index time.
 * @property year Best-effort release year, or `0` when it is absent from the source.
 * @property genre Best-effort genre tag, or `null` when unavailable.
 * @property composer Best-effort composer tag, or `null` when unavailable.
 */
@Entity(
    tableName = "tracks",
    indices = [
        Index(value = ["albumId"]),
        Index(value = ["artistId"]),
        Index(value = ["title"]),
        Index(value = ["artistName"]),
        Index(value = ["albumTitle"]),
        Index(value = ["year"]),
        Index(value = ["genre"]),
        Index(value = ["composer"]),
    ]
)
data class TrackEntity(
    @PrimaryKey val id: Long,
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
    val sampleRateHz: Int,
    val bitDepth: Int,
    val channelCount: Int,
    val isLossless: Boolean,
    val artUri: String? = null,
    val year: Int = 0,
    val genre: String? = null,
    val composer: String? = null,
)

