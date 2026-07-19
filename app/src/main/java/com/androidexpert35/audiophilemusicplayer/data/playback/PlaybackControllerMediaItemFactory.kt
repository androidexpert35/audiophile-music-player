package com.androidexpert35.audiophilemusicplayer.data.playback

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
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
            artworkUri = albumArtUri(track.albumId),
            artworkBytes = artworkBytes,
        )
        return MediaItem.fromUri(track.uri.toUri())
            .buildUpon()
            .setMediaId(track.id.toString())
            .setMediaMetadata(metadata)
            .build()
    }

    /**
     * Loads album artwork as a compressed JPEG byte array.
     *
     * @param context Application context used to read MediaStore thumbnails.
     * @param albumId MediaStore album identifier.
     * @return JPEG bytes, or `null` when artwork is unavailable.
     */
    fun loadAlbumArtBytes(context: Context, albumId: Long): ByteArray? = runCatching {
        val bitmap: Bitmap = context.contentResolver.loadThumbnail(
            albumArtUri(albumId),
            Size(ARTWORK_THUMB_SIZE, ARTWORK_THUMB_SIZE),
            null,
        )
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, ARTWORK_JPEG_QUALITY, stream)
        bitmap.recycle()
        stream.toByteArray()
    }.getOrNull()

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
        artworkUri: Uri,
        artworkBytes: ByteArray?,
    ): MediaMetadata {
        val builder = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artistName)
            .setAlbumTitle(track.albumTitle)
            .setArtworkUri(artworkUri)
        if (track.durationMs > 0L) builder.setDurationMs(track.durationMs)
        if (artworkBytes != null) {
            builder.setArtworkData(artworkBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        }
        return builder.build()
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

