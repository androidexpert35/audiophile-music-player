package com.androidexpert35.audiophilemusicplayer.domain.model.track

import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioFormat

/**
 * Domain model representing a single audio track on the device.
 *
 * @property id Unique MediaStore identifier for this track.
 * @property title Display title of the track.
 * @property artistName Name of the performing artist.
 * @property albumTitle Title of the album this track belongs to.
 * @property albumId MediaStore album identifier (used for artwork lookup).
 * @property durationMs Track duration in milliseconds.
 * @property uri Content URI string pointing to the audio file.
 * @property trackNumber Position of this track within its album disc.
 * @property discNumber Disc number for multi-disc albums.
 * @property audioFormat Encoded audio format metadata (sample rate, bit depth, codec).
 * @property fileSizeBytes File size in bytes on disk.
 * @property dateAdded Epoch seconds when the file was added to the device.
 * @property artUri Pre-computed artwork URI surfaced to the presentation layer.
 *   For regular MediaStore-indexed tracks this is the
 *   `content://media/external/audio/albumart/<albumId>` URI; for DSD files it is a
 *   `file://` path to an APIC picture extracted at scan time. `null` when absent.
 */
data class Track(
    val id: Long,
    val title: String,
    val artistName: String,
    val albumTitle: String,
    val albumId: Long,
    val durationMs: Long,
    val uri: String,
    val trackNumber: Int,
    val discNumber: Int,
    val audioFormat: AudioFormat,
    val fileSizeBytes: Long,
    val dateAdded: Long,
    val artUri: String? = null,
)

