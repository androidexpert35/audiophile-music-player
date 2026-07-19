package com.androidexpert35.audiophilemusicplayer.data.playback.usb

import com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile.AudiophileOutputSink
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Contract shared by all libusb isochronous output sinks.
 *
 * The libusb sinks ([LibusbDsdAudioSink] and [LibusbPcmAudioSink]) drive data
 * delivery entirely through a native pump thread — the Kotlin write loop in
 * [com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile.BitPerfectPlaybackEngine]
 * is bypassed for these sinks. Instead the engine detects this interface and
 * switches to a lightweight EOF-polling mode.
 *
 * ### Engine contract
 *
 * | Scenario           | Engine action                                   |
 * |--------------------|-------------------------------------------------|
 * | [eofFlag] is false | Re-post write-loop runnable after 50 ms delay  |
 * | [eofFlag] is true  | Call `onCurrentTrackEnded()` immediately        |
 * | Seek requested     | Delegate to [seekTo] — no decoder.seekTo call   |
 *
 * @see LibusbDsdAudioSink
 * @see LibusbPcmAudioSink
 */
internal interface LibusbOutputSink : AudiophileOutputSink {

    /**
     * Set to `true` by the native pump thread exactly once when the decoder
     * reports end-of-stream. Polled by the write-loop EOF check.
     */
    val eofFlag: AtomicBoolean

    /**
     * Seeks the native decoder to [positionMs] from stream start and resets
     * the wall-clock position epoch for accurate head-position reporting.
     *
     * @param positionMs Target seek position in milliseconds.
     * @return `true` when the native seek succeeded.
     */
    fun seekTo(positionMs: Long): Boolean
}

