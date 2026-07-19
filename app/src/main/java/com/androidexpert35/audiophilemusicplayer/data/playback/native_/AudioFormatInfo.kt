package com.androidexpert35.audiophilemusicplayer.data.playback.native_

import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioCodec
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.DsdRate

/**
 * Immutable snapshot of the decoded audio shape produced by [FFmpegDecoder].
 *
 * Captured once at [FFmpegDecoder.open] and held unchanged for the lifetime of
 * the decoder session — FFmpeg does not rewrite the chosen PCM target mid-file.
 *
 * @property sampleRateHz Decoded output sample rate in Hz (source-native; no
 *   resampling is performed by the decoder).
 * @property channelCount Number of interleaved PCM channels.
 * @property sourceBitDepth Bits-per-sample reported by the decoder for the
 *   original stream (16, 24, 32 …). Used for telemetry only; the on-wire
 *   carrier is dictated by [androidPcmEncoding].
 * @property androidPcmEncoding The `android.media.AudioFormat.ENCODING_PCM_*`
 *   constant that describes the byte layout produced by
 *   [FFmpegDecoder.readNextBuffer]. Passed verbatim to `AudioTrack.Builder`.
 * @property bytesPerSample Byte width of **one** PCM sample for one channel.
 *   A single "frame" on the wire is therefore
 *   `bytesPerSample * channelCount`.
 * @property durationMs Total stream duration in milliseconds, or `0` when the
 *   container exposes no duration hint.
 * @property bitrateKbps Average/declared bitrate in kilobits per second.
 *   Comes from `AVCodecContext->bit_rate` where available, otherwise falls
 *   back to the container-level bitrate. `0` means "unknown".
 * @property codec Resolved [AudioCodec] for UI/telemetry display.
 * @property codecName Raw FFmpeg codec short name such as `"aac"`, `"mp3"`, or
 *   `"wmalossless"`. Used by SUE format gating for codecs that need finer
 *   distinction than [codec] alone can provide.
 * @property codecProfileName Best-effort FFmpeg codec profile label such as
 *   `"HE-AAC"` or `"HE-AACv2"`. Empty when the decoder exposes no profile.
 * @property isDsd `true` when the decoder opened a native DSD stream rather than PCM.
 * @property dsdRate Source DSD family when [isDsd] is `true`.
 * @property dsdSourceFormat Human-readable source container label such as `DSF`, `DSDIFF`, or `WavPack DSD`.
 * @property isResampledDsd `true` when the source file is DSD but the decoder was forced
 *   into the high-res PCM fallback path (88.2 kHz / 32-bit float) because no bit-perfect DSD
 *   transport was available. Telemetry surfaces still show the DSD heritage so the user
 *   understands the file origin while knowing the carrier is PCM-resampled.
 */
data class AudioFormatInfo(
    val sampleRateHz: Int,
    val channelCount: Int,
    val sourceBitDepth: Int,
    val androidPcmEncoding: Int,
    val bytesPerSample: Int,
    val durationMs: Long,
    val bitrateKbps: Int,
    val codec: AudioCodec,
    val codecName: String = "",
    val codecProfileName: String = "",
    val isDsd: Boolean = false,
    val dsdRate: DsdRate? = null,
    val dsdSourceFormat: String? = null,
    val isResampledDsd: Boolean = false,
) {
    /** Byte size of one transport frame (all channels) on the output wire. */
    val bytesPerFrame: Int get() = bytesPerSample * channelCount
}

