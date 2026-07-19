package com.androidexpert35.audiophilemusicplayer.data.playback

import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.androidexpert35.audiophilemusicplayer.data.playback.engine.EnginePlaybackState

/**
 * Shared state-building helpers for [AudiophileSimpleBasePlayer].
 *
 * Extracts Media3 state snapshot construction so the player adapter file stays
 * focused on command handling and playlist ↔ engine coordination.
 */
internal object AudiophileSimpleBasePlayerStateFactory {
    /**
     * Resolves the Media3 playback-state constant for the current engine state.
     *
     * @param hasPlaylist Whether the adapter currently holds any media items.
     * @param enginePlayback Current engine playback state.
     * @return One of the `SimpleBasePlayer.STATE_*` constants.
     */
    fun resolveMediaState(hasPlaylist: Boolean, enginePlayback: EnginePlaybackState): Int = when {
        !hasPlaylist -> SimpleBasePlayer.STATE_IDLE
        enginePlayback == EnginePlaybackState.LOADING -> SimpleBasePlayer.STATE_BUFFERING
        enginePlayback == EnginePlaybackState.ENDED -> SimpleBasePlayer.STATE_ENDED
        enginePlayback == EnginePlaybackState.IDLE -> SimpleBasePlayer.STATE_IDLE
        enginePlayback == EnginePlaybackState.ERROR -> SimpleBasePlayer.STATE_IDLE
        else -> SimpleBasePlayer.STATE_READY
    }

    /**
     * Builds the shared available-command set exposed by the Media3 adapter.
     *
     * @return Fully populated [Player.Commands] instance.
     */
    fun buildAvailableCommands(): Player.Commands = Player.Commands.Builder()
        .addAll(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_PREPARE,
            Player.COMMAND_STOP,
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_MEDIA_ITEM,
            Player.COMMAND_SET_MEDIA_ITEM,
            Player.COMMAND_CHANGE_MEDIA_ITEMS,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_TIMELINE,
            Player.COMMAND_GET_METADATA,
            Player.COMMAND_SET_REPEAT_MODE,
            Player.COMMAND_GET_TRACKS,
            Player.COMMAND_SET_SHUFFLE_MODE,
            Player.COMMAND_RELEASE,
        )
        .build()
}

