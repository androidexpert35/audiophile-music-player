package com.androidexpert35.audiophilemusicplayer.data.scanner

import android.content.ContentResolver
import android.net.Uri
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the audio payload samples a track's content key is built from, for any file the
 * scanners can reach — MediaStore rows and granted-tree DSD documents alike.
 *
 * Both sources are opened the same way, through the resolver's file descriptor, so this
 * needs no knowledge of which scanner asked. Failure is never fatal: a revoked grant, an
 * unmounted volume or a cloud-backed provider yields [AudioContentKey.UNAVAILABLE] and the
 * scan keeps the track with an empty key rather than dropping it.
 *
 * **Threading.** The read is blocking and must be called from a coroutine already running
 * on the injected `@IoDispatcher` — which is where both scanners do all of their work.
 *
 * @property contentResolver Resolver holding the read grants for indexed audio.
 */
@Singleton
class AudioContentKeyReader @Inject constructor(
    private val contentResolver: ContentResolver,
) {

    /**
     * Derives the content key identifying the audio inside [uri].
     *
     * @param uri Content or document URI of an indexed audio file.
     * @param fileSizeBytes Size reported by the provider. `0` or negative means the
     *   provider omitted it (`COLUMN_SIZE` is optional), in which case the descriptor's own
     *   length is used instead.
     * @return The stable key, or [AudioContentKey.UNAVAILABLE] when the file cannot be read.
     */
    fun read(uri: Uri, fileSizeBytes: Long): String {
        val source = SeekableDocumentSource.open(contentResolver, uri)
            ?: return AudioContentKey.UNAVAILABLE

        return source.use { openSource ->
            val size = if (fileSizeBytes > 0L) fileSizeBytes else openSource.length()
            AudioContentKey.derive(size) { offset, length ->
                runCatching {
                    openSource.seek(offset)
                    ByteArray(length).also(openSource::readFully)
                }.getOrNull()
            }
        }
    }
}
