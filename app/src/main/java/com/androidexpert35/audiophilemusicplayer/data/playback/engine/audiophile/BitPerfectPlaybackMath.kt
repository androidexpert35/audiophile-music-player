package com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile

import kotlin.math.max

/**
 * Calculates the playback position represented by a rendered sink playhead.
 *
 * Extracted from [BitPerfectPlaybackEngine] so the engine can keep transport
 * orchestration separate from the pure timebase math.
 *
 * @param playStartPositionMs Decoder position used when the current sink began.
 * @param sinkStartFrames Playback-head frame value captured when the sink began.
 * @param playbackHeadFrames Latest playback-head frame value reported by the sink.
 * @param sampleRateHz Active playback clock rate in Hertz.
 * @return Monotonic playback position in milliseconds.
 */
internal fun calculateBitPerfectPlaybackPositionMs(
    playStartPositionMs: Long,
    sinkStartFrames: Long,
    playbackHeadFrames: Long,
    sampleRateHz: Int,
): Long {
    val safeSampleRateHz = sampleRateHz.coerceAtLeast(1).toLong()
    val renderedFrames = (playbackHeadFrames - sinkStartFrames).coerceAtLeast(0L)
    val elapsedMs = renderedFrames * MS_PER_SEC / safeSampleRateHz
    return max(0L, playStartPositionMs + elapsedMs)
}

private const val MS_PER_SEC = 1_000L

