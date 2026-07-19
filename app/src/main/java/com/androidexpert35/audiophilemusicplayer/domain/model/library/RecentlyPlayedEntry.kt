package com.androidexpert35.audiophilemusicplayer.domain.model.library

/**
 * Records a single playback event for the recently-played history.
 *
 * Only the most recent play of each track is retained — if the same track is
 * played twice, the earlier entry is replaced so the history stays compact.
 *
 * @property trackId Stable MediaStore identifier of the played track.
 * @property playedAt Epoch milliseconds when playback of this track began.
 */
data class RecentlyPlayedEntry(
    val trackId: Long,
    val playedAt: Long
)

