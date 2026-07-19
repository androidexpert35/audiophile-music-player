package com.androidexpert35.audiophilemusicplayer.domain.model.common

import com.tony.coreui.domain.resource.ResourceError

/**
 * Preserves playback-specific failure details that are meaningful to Audiophile.
 *
 * @property message User-facing or diagnostic description of the playback failure.
 * @property errorCode Optional Media3 or platform playback error code.
 */
data class PlaybackResourceError(
    val message: String,
    val errorCode: Int? = null
) : ResourceError
