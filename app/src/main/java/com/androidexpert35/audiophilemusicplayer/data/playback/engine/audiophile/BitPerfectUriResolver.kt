package com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile

import android.content.Context
import androidx.core.net.toUri

/**
 * Resolves a raw URI string to a file-system path that FFmpeg can open.
 *
 * Supports three URI schemes:
 * - `null` / `"file"` — returns the path component directly.
 * - `"content"` — opens a file descriptor via [android.content.ContentResolver]
 *   and returns the `/proc/self/fd/<fd>` trampoline path that FFmpeg's file
 *   protocol handler can read.
 * - Any other scheme — returned as-is (network streams, custom protocols).
 *
 * The file descriptor produced for `content://` URIs is intentionally **not**
 * closed here. It is handed directly to FFmpeg and must remain open for the
 * decoder's lifetime. The OS reclaims it when FFmpeg releases the format
 * context via `avformat_close_input`.
 *
 * @param context Application context used for [android.content.ContentResolver]
 *   resolution.
 * @param raw Raw URI string as stored in [com.androidexpert35.audiophilemusicplayer.domain.model.track.Track.uri].
 * @return A file-system path or fd trampoline that FFmpeg can open.
 * @throws IllegalStateException when a `content://` URI cannot be opened via
 *   the content resolver (e.g. cloud-backed file, revoked permission).
 */
internal fun resolveUriToPath(context: Context, raw: String): String {
    val uri = raw.toUri()
    return when (uri.scheme) {
        null, "file" -> uri.path ?: raw
        "content" -> {
            // Preferred path — file-descriptor trampoline. Works on every OEM
            // without requiring the MANAGE_EXTERNAL_STORAGE permission.
            context.contentResolver.openFileDescriptor(uri, "r")
                ?.use { pfd ->
                    val fd = pfd.detachFd()
                    "/proc/self/fd/$fd"
                }
                ?: error("ContentResolver.openFileDescriptor returned null for $raw")
        }
        else -> raw
    }
}

