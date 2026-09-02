package com.androidexpert35.audiophilemusicplayer.data.playback

import android.content.Context
import androidx.core.net.toUri

/**
 * Resolves a raw URI string to a file-system path that FFmpeg can open.
 *
 * Shared by every component that hands a library URI to a native FFmpeg
 * session: the bit-perfect playback engine
 * ([com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile.BitPerfectPlaybackEngine])
 * and the offline measurement pass
 * ([com.androidexpert35.audiophilemusicplayer.data.playback.analysis.TrackSignalAnalyser]).
 * It lives one level above `engine/` precisely so the second of those does not
 * have to reach into engine-private code or keep a second copy of the rules —
 * a copy that would drift the first time an OEM quirk forces a change here.
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
 * `content://` resolution performs a binder round-trip and can touch storage,
 * so callers must invoke this off the main thread and off the engine's audio
 * thread.
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
