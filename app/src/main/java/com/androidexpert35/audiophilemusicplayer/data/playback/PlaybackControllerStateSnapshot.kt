package com.androidexpert35.audiophilemusicplayer.data.playback

import androidx.media3.session.MediaController
import com.androidexpert35.audiophilemusicplayer.domain.model.playback.PlaybackState
import com.androidexpert35.audiophilemusicplayer.domain.model.playback.QueueState
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track

/**
 * Replaces the controller track lookup map with [tracks].
 *
 * @receiver Mutable mediaId → track map maintained by the controller.
 * @param tracks Queue tracks that should become the authoritative lookup source.
 */
internal fun MutableMap<String, Track>.replaceWithTracks(tracks: List<Track>) {
    clear()
    tracks.associateByTo(this) { track -> track.id.toString() }
}

/**
 * Resolves the current queue item from [trackMap], falling back to the last
 * published controller state when Media3 has not exposed a current item yet.
 *
 * @param ctrl Media3 controller snapshot.
 * @param trackMap Current mediaId → track lookup table.
 * @param fallbackState Last published playback state.
 * @return Resolved current track, or `null` when unavailable.
 */
internal fun resolveCurrentTrack(
    ctrl: MediaController,
    trackMap: Map<String, Track>,
    fallbackState: PlaybackState,
): Track? = ctrl.currentMediaItem?.let { item -> trackMap[item.mediaId] }
    ?: fallbackState.currentTrack

/**
 * Resolves the best currently known duration for the active item.
 *
 * @param ctrl Media3 controller, or `null` when disconnected.
 * @param fallbackTrack Domain track used as a duration fallback.
 * @param preferredDurationMs Preferred duration when an optimistic seek already published one.
 * @return Best available duration in milliseconds.
 */
internal fun resolveDurationMs(
    ctrl: MediaController?,
    fallbackTrack: Track?,
    preferredDurationMs: Long = 0L,
): Long = preferredDurationMs
    .takeIf { it > 0L }
    ?: ctrl?.duration
        ?.coerceAtLeast(0L)
        ?.takeIf { it > 0L }
    ?: fallbackTrack?.durationMs
    ?: 0L

/**
 * Builds a domain [QueueState] snapshot from the current Media3 controller state.
 *
 * @param ctrl Active Media3 controller.
 * @param tracks Queue tracks resolved from the controller playlist.
 * @param fallbackQueueState Last published queue state.
 * @param currentIndex Optional explicit current index override.
 * @return Domain queue snapshot for UI consumption.
 */
internal fun buildQueueStateSnapshot(
    ctrl: MediaController,
    tracks: List<Track>,
    fallbackQueueState: QueueState,
    currentIndex: Int = ctrl.currentMediaItemIndex.takeIf { it >= 0 } ?: fallbackQueueState.currentIndex,
): QueueState = QueueState(
    tracks = tracks,
    currentIndex = currentIndex,
    repeatMode = PlaybackStateMapper.fromMedia3RepeatMode(ctrl.repeatMode),
    shuffleMode = PlaybackStateMapper.fromMedia3ShuffleEnabled(ctrl.shuffleModeEnabled),
)

