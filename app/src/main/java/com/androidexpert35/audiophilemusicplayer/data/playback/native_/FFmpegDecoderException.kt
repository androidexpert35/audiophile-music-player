package com.androidexpert35.audiophilemusicplayer.data.playback.native_

/**
 * Thrown when the native FFmpeg layer fails a slow-path operation
 * ([FFmpegDecoder.open] / [FFmpegDecoder.seekTo]).
 *
 * Hot-path failures in [FFmpegDecoder.readNextBuffer] are surfaced as the
 * negative sentinel [FFmpegDecoder.READ_RECOVERABLE_ERROR] instead of
 * throwing, to avoid JNI exception overhead on the audio thread.
 *
 * @constructor Creates an exception carrying a human-readable description of
 *   the underlying AVERROR code plus the context in which it fired.
 * @param message Free-form description provided by the native layer (includes
 *   the FFmpeg `av_strerror` translation of the underlying error code).
 */
class FFmpegDecoderException(message: String) : RuntimeException(message)

