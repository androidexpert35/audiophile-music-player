package com.androidexpert35.audiophilemusicplayer.domain.model.analysis

/**
 * Integral ("Class I") loudness measurements cached for one piece of audio.
 *
 * Unlike [StationaryAnalysis] these are properties of the *whole* stream and
 * cannot be sampled: a peak seen in three windows is not the peak of the track,
 * and an underestimated peak is worse than no peak because a gain stage will
 * trust it. They are therefore only ever written from a pass that saw every
 * sample — a complete listen or a full-file offline decode.
 *
 * The two classes are produced by different passes at different times, so a
 * cached analysis routinely carries one and not the other.
 *
 * @property peakDbfs Absolute sample peak of the stream in dBFS, `0.0` at full
 *   scale and negative below it. `null` when no complete pass has run.
 * @property integratedLufs EBU R128 integrated loudness in LUFS.
 * @property plr Peak-to-loudness ratio in dB, i.e. [peakDbfs] minus
 *   [integratedLufs]; the measured dynamic headroom of the master.
 * @property clippingRatio Fraction of samples sitting at or beyond full scale,
 *   in `[0, 1]`. A non-trivial value means the source was already clipped
 *   before this app ever touched it.
 */
data class IntegralAnalysis(
    val peakDbfs: Double?,
    val integratedLufs: Double?,
    val plr: Double?,
    val clippingRatio: Double?,
)
