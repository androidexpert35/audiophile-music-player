package com.androidexpert35.audiophilemusicplayer.data.scanner

import android.annotation.SuppressLint
import android.provider.MediaStore
import com.androidexpert35.audiophilemusicplayer.data.scanner.MediaStoreColumns.trackSelectionForFolders

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

    /**
     * Base selection clause filtering out short audio files (ringtones, notifications).
     *
     * Always combined with a folder restriction built by [MediaStoreScanner] — the scan
     * is scoped to the locations the user granted, never to the whole device.
     */
    const val TRACK_SELECTION = "${MediaStore.Audio.Media.DURATION} > 30000"

    /**
     * Escape character used with `LIKE` so folder names containing SQL wildcards
     * (`%`, `_`) match literally instead of broadening the scan.
     */
    const val LIKE_ESCAPE_CHAR = "\\"

    /** Default sort order: title ascending for predictable library ordering. */
    const val TRACK_SORT_ORDER = "${MediaStore.Audio.Media.TITLE} ASC"

    /**
     * Builds the `WHERE` clause restricting a track query to the granted music folders.
     *
     * Each folder contributes a volume equality plus a `RELATIVE_PATH` prefix match, so a
     * grant covers the folder and everything beneath it. The volume is matched explicitly
     * because `RELATIVE_PATH` alone is ambiguous — `Music/` exists on internal storage and
     * on a removable card alike.
     *
     * @param folders Non-empty list of granted locations.
     * @return Selection string with one placeholder pair per folder, in folder order.
     */
    fun trackSelectionForFolders(folders: List<MusicFolderScope>): String {
        val folderClause = folders.joinToString(separator = " OR ") { _ ->
            "(${MediaStore.Audio.Media.VOLUME_NAME} = ? AND " +
                "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ? ESCAPE '$LIKE_ESCAPE_CHAR')"
        }
        return "$TRACK_SELECTION AND ($folderClause)"
    }

    /**
     * Produces the selection arguments matching [trackSelectionForFolders], in the same
     * folder order: volume name followed by the escaped path prefix pattern.
     *
     * @param folders Non-empty list of granted locations.
     * @return Flat argument array of `volumeName, pathPattern` pairs.
     */
    fun trackSelectionArgsForFolders(folders: List<MusicFolderScope>): Array<String> =
        folders.flatMap { folder ->
            listOf(folder.volumeName, "${folder.relativePath.escapeForLike()}%")
        }.toTypedArray()

    /**
     * Escapes SQL `LIKE` wildcards so a folder literally named `Hi_Res` or `100%` matches
     * only itself instead of expanding the scan to unrelated paths.
     */
    private fun String.escapeForLike(): String =
        replace(LIKE_ESCAPE_CHAR, "$LIKE_ESCAPE_CHAR$LIKE_ESCAPE_CHAR")
            .replace("%", "$LIKE_ESCAPE_CHAR%")
            .replace("_", "${LIKE_ESCAPE_CHAR}_")
}
