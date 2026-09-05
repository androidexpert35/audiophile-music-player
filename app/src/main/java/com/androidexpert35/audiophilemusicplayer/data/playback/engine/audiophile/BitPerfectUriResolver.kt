package com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.core.net.toUri
import java.io.Closeable

/**
 * Owns the open source a loaded track is decoded from, for that track's lifetime.
 *
 * For `content://` tracks the [path] is a `/proc/self/fd/<n>` trampoline that only
 * names a descriptor this handle keeps open — the number is meaningless once the
 * descriptor is gone. Every decoder session opened from [path] takes its own
 * `dup()` of it (`ffmpeg_session_open` in `ffmpeg_bridge.cpp`), so the handle has
 * to outlive **session building**, not the sessions themselves: the tier-1 probe,
 * the tier-3 fallback, and the libusb sink's own native decoder all resolve the
 * same string while a track is being loaded.
 *
 * The owner is therefore [BitPerfectPlaybackEngine], which closes the handle when
 * the track it belongs to is replaced or stopped — and [BitPerfectGaplessQueue],
 * which holds the preloaded track's handle until the gapless swap transfers it to
 * the engine or the preload is discarded.
 *
 * @property path Path or fd trampoline handed to FFmpeg. Valid only while this
 *   handle is open.
 */
internal class BitPerfectSourceHandle private constructor(
    val path: String,
    private val descriptor: ParcelFileDescriptor?,
) : Closeable {

    private var closed = false

    /**
     * Releases the descriptor backing [path], if there is one.
     *
     * Idempotent: a handle that is closed twice (stop racing a track replacement)
     * releases nothing the second time, so a descriptor number this process has
     * already recycled can never be closed out from under its new owner.
     */
    override fun close() {
        if (closed) return
        closed = true
        runCatching { descriptor?.close() }
    }

    companion object {

        /**
         * Wraps a path that owns nothing — a `file://` track, a network stream, or
         * any scheme FFmpeg opens by itself. Closing such a handle is a no-op.
         */
        fun ofPath(path: String): BitPerfectSourceHandle =
            BitPerfectSourceHandle(path, descriptor = null)

        /**
         * Wraps an open [descriptor] as its `/proc/self/fd/<n>` trampoline path.
         *
         * The descriptor is **not** detached: this handle keeps the
         * [ParcelFileDescriptor] and closes it in [close]. Detaching would strand
         * the raw descriptor with no owner at all — the leak this type exists to
         * prevent.
         */
        fun ofDescriptor(descriptor: ParcelFileDescriptor): BitPerfectSourceHandle =
            BitPerfectSourceHandle("/proc/self/fd/${descriptor.fd}", descriptor)
    }
}

/**
 * Resolves a raw URI string to a source FFmpeg can open, owned by the caller.
 *
 * Supports three URI schemes:
 * - `null` / `"file"` — the path component, owning nothing.
 * - `"content"` — opens a file descriptor via [android.content.ContentResolver]
 *   and returns it as the `/proc/self/fd/<fd>` trampoline the native decoder
 *   reads through. The descriptor carries the SAF grant that makes `.dsf`/`.dff`
 *   readable at all, so it must stay open for as long as the path may be opened
 *   (`docs/BIT_PERFECT_LIMITATIONS.md` §7).
 * - Any other scheme — returned as-is (network streams, custom protocols).
 *
 * **The caller owns the result and must [BitPerfectSourceHandle.close] it** once
 * the track it was resolved for is no longer loaded. Returning a bare path here
 * instead leaked one descriptor per track load, pause-resume, settings reload,
 * and gapless preload.
 *
 * @param context Application context used for [android.content.ContentResolver]
 *   resolution.
 * @param raw Raw URI string as stored in [com.androidexpert35.audiophilemusicplayer.domain.model.track.Track.uri].
 * @return An open [BitPerfectSourceHandle] the caller is responsible for closing.
 * @throws IllegalStateException when a `content://` URI cannot be opened via
 *   the content resolver (e.g. cloud-backed file, revoked permission).
 */
internal fun resolveUriToSource(context: Context, raw: String): BitPerfectSourceHandle {
    val uri = raw.toUri()
    return when (uri.scheme) {
        null, "file" -> BitPerfectSourceHandle.ofPath(uri.path ?: raw)
        "content" -> {
            // Preferred path — file-descriptor trampoline. Works on every OEM
            // without requiring the MANAGE_EXTERNAL_STORAGE permission.
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                ?: error("ContentResolver.openFileDescriptor returned null for $raw")
            BitPerfectSourceHandle.ofDescriptor(descriptor)
        }
        else -> BitPerfectSourceHandle.ofPath(raw)
    }
}
