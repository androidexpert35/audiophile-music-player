package com.androidexpert35.audiophilemusicplayer.data.playback.analysis

/**
 * Integral ("Class I") signal measurements taken over a whole decoded stream.
 *
 * These describe the master rather than a moment of it: how loud it was made, how much
 * headroom was left above that loudness, and how much of it was pushed into the ceiling.
 * None of them can be sampled — a peak seen in three windows is not the peak of the
 * track, and an underestimated peak is worse than no peak because a gain stage will
 * trust it — so an instance of this class only ever comes from a pass that saw every
 * sample.
 *
 * Stationary measures (spectral cutoff, tilt, stereo relationships) are a different pass
 * and are deliberately absent here; see [AudioAnalysisFeatures].
 *
 * Every measured value is nullable: a statistic the graph never emitted (a stream too
 * short for EBU R128's 400 ms gating block, a track with no flat-top run at all) is
 * reported as `null` rather than as a plausible-looking zero.
 *
 * @property samplePeakDbfs Absolute sample peak of the stream in dBFS, counted from the
 *   decoded samples rather than read out of filter metadata. `0.0` at full scale.
 * @property truePeakDbfs Inter-sample (true) peak in dBFS as reported by `ebur128`. Can
 *   legitimately exceed `0.0`: a signal whose samples all sit inside full scale may still
 *   overshoot between them, which is what clips a DAC's reconstruction filter.
 * @property integratedLufs EBU R128 integrated loudness in LUFS.
 * @property plrDb Peak-to-loudness ratio in dB — [samplePeakDbfs] minus
 *   [integratedLufs] — the measured dynamic headroom of the master.
 * @property clippingRatio Fraction of individual samples at or beyond full scale, in
 *   `[0, 1]`. A non-trivial value means the source arrived clipped; this player never
 *   introduces clipping of its own.
 * @property flatRunCount Number of flat-top runs found, counted per channel. A flat top
 *   is a plateau of consecutive full-scale samples — what clipping actually leaves
 *   behind, as opposed to an isolated sample that merely touches the ceiling.
 * @property flatRunLongestSamples Length in samples of the longest flat-top run.
 * @property flatRunMeanSamples Mean length in samples of a flat-top run.
 * @property flatRunSampleRatio Fraction of all samples sitting inside a flat-top run, in
 *   `[0, 1]`. This is what separates a master with a handful of clipped transients from
 *   one that is squashed flat for minutes at a time.
 * @property frameCount PCM frames the aggregate covers.
 */
data class AudioIntegralFeatures(
    val samplePeakDbfs: Double?,
    val truePeakDbfs: Double?,
    val integratedLufs: Double?,
    val plrDb: Double?,
    val clippingRatio: Double?,
    val flatRunCount: Long,
    val flatRunLongestSamples: Double?,
    val flatRunMeanSamples: Double?,
    val flatRunSampleRatio: Double?,
    val frameCount: Long,
) {
    /**
     * `true` when the pass produced nothing usable.
     *
     * A caller should treat this exactly like a failed measurement and not persist the
     * row: it means no audio ever reached the graph, so every value in it is absent
     * rather than measured.
     */
    val isEmpty: Boolean
        get() = frameCount == 0L
}
