package com.androidexpert35.audiophilemusicplayer.data.playback.focus

/** Describes ownership changes for the app's manually managed media audio focus. */
internal enum class AudioFocusState {
    REQUESTING,
    GRANTED,
    DENIED,
    LOST,
}
