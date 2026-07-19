package com.androidexpert35.audiophilemusicplayer.data.playback.engine.standard

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Extracts best-effort encoded audio metadata for the Standard playback engine.
 *
 * Media3 exposes the selected input format during playback, but it may omit bit
 * depth for compressed tracks. This resolver probes the currently loaded URI via
 * [MediaMetadataRetriever] so Standard-mode telemetry can still surface sample
 * rate, bit depth, and bitrate when the runtime format leaves them blank.
 *
 * @property context Application context used to open content URIs safely.
 */
@Singleton
internal class StandardTrackMetadataResolver @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    /**
     * Reads encoded audio metadata from the provided track URI.
     *
     * @param uriString Content URI string for the currently loaded track.
     * @return [StandardTrackMetadata] when at least one useful field is present,
     *         otherwise `null`.
     */
    fun resolve(uriString: String): StandardTrackMetadata? = runCatching {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, Uri.parse(uriString))
        retriever.use {
            val sampleRateHz = it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)
                ?.toIntOrNull()
                ?.coerceAtLeast(0)
                ?: 0
            val bitDepth = it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)
                ?.toIntOrNull()
                ?.coerceAtLeast(0)
                ?: 0
            val bitrateKbps = it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toIntOrNull()
                ?.coerceAtLeast(0)
                ?.div(1_000)
                ?: 0

            StandardTrackMetadata(
                sampleRateHz = sampleRateHz,
                bitDepth = bitDepth,
                bitrateKbps = bitrateKbps,
            ).takeUnless { metadata ->
                metadata.sampleRateHz == 0 &&
                    metadata.bitDepth == 0 &&
                    metadata.bitrateKbps == 0
            }
        }
    }.getOrNull()
}

