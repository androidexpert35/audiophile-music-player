package com.androidexpert35.audiophilemusicplayer.data.playback

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Asynchronously enriches the current [MediaItem] with embedded album artwork bytes.
 *
 * Called on every [androidx.media3.common.Player.Listener.onMediaItemTransition]
 * so that the system notification always displays album art after a gapless
 * transition, a skip-next/previous, or a session restoration — cases where the
 * queue items were built as lightweight restoration items that carry only an
 * artwork URI.
 *
 * Some vendor ROMs (ColorOS, MIUI, OneUI) do not resolve `content://` album URIs
 * from the notification shade. Embedding raw JPEG bytes via
 * [MediaMetadata.artworkData] guarantees artwork is rendered regardless of OEM
 * image-loading restrictions.
 *
 * The operation is a no-op when:
 * - The track cannot be resolved from [trackMap] (stale or unknown media ID).
 * - The item already carries embedded [MediaMetadata.artworkData] (avoids redundant IO).
 * - The item changes again while artwork is loading (stale capture guard).
 * - No artwork is available from the track's cached or MediaStore artwork URI.
 *
 * Uses [MediaController.replaceMediaItem] with the same URI so ExoPlayer detects no
 * source change, performs a metadata-only update, and does not rebuffer the current track.
 *
 * @param mediaItem The [MediaItem] that just became current.
 * @param controllerRef Nullable reference to the active [MediaController].
 * @param trackMap Reverse-lookup map of media ID → domain track.
 * @param mainScope Application-scoped main-thread coroutine scope.
 * @param ioDispatcher Dispatcher used for the blocking artwork content-resolver read.
 * @param context Application context for [PlaybackControllerMediaItemFactory.loadArtworkBytes].
 */
@OptIn(UnstableApi::class)
internal fun enrichCurrentMediaItemArtwork(
    mediaItem: MediaItem,
    controllerRef: MediaController?,
    trackMap: Map<String, com.androidexpert35.audiophilemusicplayer.domain.model.track.Track>,
    mainScope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
    context: Context,
) {
    val ctrl = controllerRef ?: return
    val track = trackMap[mediaItem.mediaId] ?: return
    val capturedIndex = ctrl.currentMediaItemIndex.takeIf { it >= 0 } ?: return
    val capturedMediaId = mediaItem.mediaId

    mainScope.launch {
        val artworkBytes = withContext(ioDispatcher) {
            PlaybackControllerMediaItemFactory.loadArtworkBytes(context, track)
        }
            ?: return@launch // No artwork available — leave the existing item unchanged

        // Validate that the current item hasn't changed during the IO load.
        val currentItem = ctrl.currentMediaItem ?: return@launch
        if (currentItem.mediaId != capturedMediaId) return@launch // Track changed while loading

        // Skip if the item already carries embedded art (e.g. it was built via toMediaItem()).
        if (currentItem.mediaMetadata.artworkData != null) return@launch

        val updatedMetadata = currentItem.mediaMetadata.buildUpon()
            .setArtworkData(artworkBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            .build()
        val updatedItem = currentItem.buildUpon()
            .setMediaMetadata(updatedMetadata)
            .build()

        // replaceMediaItem with the same URI performs a metadata-only patch;
        // ExoPlayer does not re-prepare or rebuffer the current source.
        ctrl.replaceMediaItem(capturedIndex, updatedItem)
        Log.d(TAG, "Notification artwork enriched for: ${track.title}")
    }
}

private const val TAG = "PlaybackController"

