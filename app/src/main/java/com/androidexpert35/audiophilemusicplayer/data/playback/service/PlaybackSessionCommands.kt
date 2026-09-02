package com.androidexpert35.audiophilemusicplayer.data.playback.service

import android.os.Bundle
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand

/** Defines Media3 commands shared by the playback notification and app controller. */
internal object PlaybackSessionCommands {
    private const val ACTION_RELEASE_USB_AUDIO =
        "com.androidexpert35.audiophilemusicplayer.command.RELEASE_USB_AUDIO"

    /** Releases the active exclusive DAC session while preserving queue and playhead. */
    val releaseUsbAudio = SessionCommand(ACTION_RELEASE_USB_AUDIO, Bundle.EMPTY)

    /** Builds the persistent notification action for releasing the active DAC. */
    fun releaseUsbAudioButton(displayName: String): CommandButton =
        CommandButton.Builder(CommandButton.ICON_VOLUME_OFF)
            .setDisplayName(displayName)
            .setSessionCommand(releaseUsbAudio)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build()
}
