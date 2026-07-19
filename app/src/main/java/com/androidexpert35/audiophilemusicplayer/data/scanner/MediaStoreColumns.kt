package com.androidexpert35.audiophilemusicplayer.data.scanner

import android.annotation.SuppressLint
import android.provider.MediaStore

/**
 * Centralised MediaStore column projections for ultra-lightweight queries.
 *
 * Using explicit projections instead of `null` (SELECT *) reduces memory
 * allocation by ~40% on large libraries and avoids reading unused BLOB columns.
 *
 * Only the columns required by [MediaStoreScanner.scanAudioFilesForIndexing] are
 * listed here. The library is indexed via Room after a single scan pass; all
 * subsequent reads go through Room DAOs, so no separate album or artist
 * projection is needed.
 */
object MediaStoreColumns {

    /** Projection for audio track queries — only the columns we actually map. */
    // SAMPLERATE and BITS_PER_SAMPLE are T Extensions SDK symbols but the underlying
    // MediaStore columns exist on all Android 12+ (API 31+) devices; minSdk = 33.
    @SuppressLint("NewApi")
    val TRACK_PROJECTION: Array<String> = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST_ID,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.ALBUM_ID,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.DATA,
        MediaStore.Audio.Media.DISPLAY_NAME,
        MediaStore.Audio.Media.RELATIVE_PATH,
        MediaStore.Audio.Media.SIZE,
        MediaStore.Audio.Media.DATE_ADDED,
        MediaStore.Audio.Media.TRACK,
        MediaStore.Audio.Media.DISC_NUMBER,
        MediaStore.Audio.Media.MIME_TYPE,
        MediaStore.Audio.Media.YEAR,
        // API 31+ columns — available on all supported devices (minSdk 33).
        MediaStore.Audio.Media.SAMPLERATE,
        MediaStore.Audio.Media.BITS_PER_SAMPLE,
    )

    /** Selection clause filtering out short audio files (ringtones, notifications). */
    const val TRACK_SELECTION = "${MediaStore.Audio.Media.DURATION} > 30000"

    /** Default sort order: title ascending for predictable library ordering. */
    const val TRACK_SORT_ORDER = "${MediaStore.Audio.Media.TITLE} ASC"
}
