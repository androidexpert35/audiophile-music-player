package com.androidexpert35.audiophilemusicplayer.data.playback

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track
import java.io.ByteArrayOutputStream

/**
 * Builds Media3 [MediaItem]s and artwork payloads for the playback controller.
 *
 * Keeps Media3 metadata construction and notification-artwork serialization out
 * of [PlaybackController] so the controller can focus on transport commands and
 * state publication.
 */
@OptIn(UnstableApi::class)
internal object PlaybackControllerMediaItemFactory {
    private const val ARTWORK_THUMB_SIZE = 512
    private const val ARTWORK_JPEG_QUALITY = 85

    /**
     * Converts a domain [Track] into a richly populated [MediaItem].
     *
     * @param track Track that should become a queue item.
     * @param artworkBytes Optional embedded JPEG bytes for notification artwork.
     * @return Media3 item carrying the track URI and metadata.
     */
    fun createMediaItem(track: Track, artworkBytes: ByteArray?): MediaItem {
        val metadata = buildTrackMetadata(
            track = track,
            artworkUri = artworkUri(track),
            artworkBytes = artworkBytes,
        )
        return MediaItem.fromUri(track.uri.toUri())
            .buildUpon()
            .setMediaId(track.id.toString())
            .setMediaMetadata(metadata)
            .build()
    }

    /**
     * Loads track artwork as a notification-safe compressed JPEG byte array.
     *
     * Uses the pre-computed [Track.artUri] when available, including the cached
     * `file://` artwork extracted from DSF tags, then falls back to MediaStore for
     * regular tracks. File-backed images are sampled before decoding to avoid
     * retaining a full-resolution embedded cover in memory.
     *
     * @param context Application context used to read artwork.
     * @param track Track whose artwork should be loaded.
     * @return JPEG bytes, or `null` when artwork is unavailable.
     */
    fun loadArtworkBytes(context: Context, track: Track): ByteArray? = runCatching {
        val artworkUri = artworkUri(track) ?: return@runCatching null
        val bitmap = if (artworkUri.scheme == ContentResolver.SCHEME_FILE) {
            loadSampledFileBitmap(artworkUri)
        } else {
            context.contentResolver.loadThumbnail(
                artworkUri,
                Size(ARTWORK_THUMB_SIZE, ARTWORK_THUMB_SIZE),
                null,
            )
        } ?: return@runCatching null
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, ARTWORK_JPEG_QUALITY, stream)
        bitmap.recycle()
        stream.toByteArray()
    }.getOrNull()

    /**
     * Resolves the artwork URI carried into Media3 metadata for [track].
     *
     * @param track Track whose local artwork source should be exposed.
     * @return Cached or MediaStore artwork URI, or `null` for an untagged DSD track.
     */
    fun artworkUri(track: Track): Uri? = track.artUri
        ?.takeIf(String::isNotBlank)
        ?.toUri()
        ?: track.albumId.takeIf { it > 0L }?.let(::albumArtUri)

    /**
     * Returns the MediaStore album-art URI for [albumId].
     *
     * @param albumId MediaStore album identifier.
     * @return Album-art content URI.
     */
    fun albumArtUri(albumId: Long): Uri = ContentUris.withAppendedId(
        MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI,
        albumId,
    )

    private fun buildTrackMetadata(
        track: Track,
        artworkUri: Uri?,
        artworkBytes: ByteArray?,
    ): MediaMetadata {
        val builder = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artistName)
            .setAlbumTitle(track.albumTitle)
        if (artworkUri != null) builder.setArtworkUri(artworkUri)
        if (track.durationMs > 0L) builder.setDurationMs(track.durationMs)
        if (artworkBytes != null) {
            builder.setArtworkData(artworkBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        }
        return builder.build()
    }

    private fun loadSampledFileBitmap(artworkUri: Uri): Bitmap? {
        val path = artworkUri.path ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (
            bounds.outWidth / sampleSize > ARTWORK_THUMB_SIZE * 2 ||
            bounds.outHeight / sampleSize > ARTWORK_THUMB_SIZE * 2
        ) {
            sampleSize *= 2
        }
        val decoded = BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        ) ?: return null
        if (decoded.width <= ARTWORK_THUMB_SIZE && decoded.height <= ARTWORK_THUMB_SIZE) {
            return decoded
        }

        val scale = ARTWORK_THUMB_SIZE.toFloat() / maxOf(decoded.width, decoded.height)
        val thumbnail = Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        decoded.recycle()
        return thumbnail
    }
}

/**
 * Builds a full [MediaItem] for immediate playback, including optional artwork.
 *
 * @receiver Track that should be converted.
 * @param artworkBytes Optional JPEG artwork payload.
 * @return Rich Media3 queue item.
 */
internal fun Track.toPlaybackMediaItem(artworkBytes: ByteArray? = null): MediaItem =
    PlaybackControllerMediaItemFactory.createMediaItem(this, artworkBytes)

/**
 * Builds the lightweight restoration-time [MediaItem] variant for this track.
 *
 * @receiver Track that should be restored into the queue.
 * @return Media3 item without embedded artwork bytes.
 */
internal fun Track.toRestorationMediaItem(): MediaItem = toPlaybackMediaItem(artworkBytes = null)

