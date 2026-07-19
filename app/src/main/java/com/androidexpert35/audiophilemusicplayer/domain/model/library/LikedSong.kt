package com.androidexpert35.audiophilemusicplayer.domain.model.library

/**
 * Represents a track that the user has marked as liked/favourite.
 *
 * @property trackId Stable MediaStore identifier of the liked track.
 * @property likedAt Epoch milliseconds when the track was liked.
 */
data class LikedSong(
    val trackId: Long,
    val likedAt: Long
)

