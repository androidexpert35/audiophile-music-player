package com.androidexpert35.audiophilemusicplayer.data.playback.analysis

import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioCodec

/**
 * The minimum a track has to say about itself before its signal can be measured.
 *
 * Deliberately narrower than
 * [com.androidexpert35.audiophilemusicplayer.domain.model.track.Track]: the measurement
 * pass needs the content key (which the domain track does not carry — it is a column on
 * the cached row, see `docs/ai/data.md`), somewhere to read the bytes from, and just
 * enough format information to decide the cheap skips without opening a decoder.
 * Everything else about a track — title, artist, artwork — is irrelevant to a
 * measurement and is left out so it cannot be mistaken for an input.
 *
 * @property audioKey Content key of the audio, as stored on the cached track row. Blank
 *   means the file could not be sampled at scan time, which makes it unanalysable rather
 *   than an error.
 * @property uri Source URI string; resolved to an FFmpeg-readable path at measurement
 *   time by [com.androidexpert35.audiophilemusicplayer.data.playback.resolveUriToPath].
 * @property durationMs Track duration in milliseconds, used for the too-short skip and
 *   as the hint for spreading the sample windows.
 * @property codec Codec resolved at scan time, used only to skip DSD sources before any
 *   decoder is opened.
 */
data class AnalysableTrack(
    val audioKey: String,
    val uri: String,
    val durationMs: Long,
    val codec: AudioCodec,
) {
    /**
     * `true` when the scan metadata already identifies this as a DSD source.
     *
     * DSD never reaches the DSP stage — the bit-perfect transports carry it straight to
     * the DAC — so measuring it would produce numbers no decision ever reads. This is the
     * cheap check; the decoder confirms it again once the stream is actually open, for
     * files whose container MIME lied at scan time.
     */
    val isDsdSource: Boolean
        get() = codec == AudioCodec.DSD_64 ||
            codec == AudioCodec.DSD_128 ||
            codec == AudioCodec.DSD_256
}
