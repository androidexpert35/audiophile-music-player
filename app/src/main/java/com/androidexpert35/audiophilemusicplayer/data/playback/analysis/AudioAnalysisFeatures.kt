package com.androidexpert35.audiophilemusicplayer.data.playback.analysis

/**
 * Stationary ("Class S") signal measurements taken from a sample of a track.
 *
 * These are the properties of the audio that do not depend on where in the file
 * they were measured — how far up the spectrum the source actually carries
 * energy, how the energy is tilted across it, and how the two channels relate.
 * They are what a DSP stage needs in order to stop guessing a source's low-pass
 * cutoff from its codec and bitrate.
 *
 * Integral measures (true peak, integrated loudness, clipping ratio) are a
 * different pass over the whole file and are deliberately absent here.
 *
 * Every measured value is nullable: a statistic the filter graph never emitted
 * (silence, a source too short to fill one analysis window) is reported as
 * `null` rather than as a plausible-looking zero.
 *
 * @property spectralRolloffHz Frequency below which effectively all energy
 *   lies, averaged across windows and channels — the measured counterpart of
 *   the encoder low-pass a lossy source was produced with.
 * @property spectralCentroidHz Centre of mass of the spectrum in Hz; the
 *   perceptual "brightness" of the source.
 * @property spectralSlope Tilt of the spectrum. Negative values fall away
 *   towards the treble (dark), values near zero are spectrally flat.
 * @property noiseFloorDbfs Estimated noise floor in dBFS.
 * @property dcOffset Mean sample offset in `[-1, 1]`; anything far from zero
 *   indicates a source with a DC bias.
 * @property leftRmsDbfs RMS energy of the first channel in dBFS.
 * @property rightRmsDbfs RMS energy of the second channel in dBFS; equal to
 *   [leftRmsDbfs] for mono sources.
 * @property midRmsDbfs RMS energy of the `(L+R)/2` mid signal in dBFS.
 * @property sideRmsDbfs RMS energy of the `(L-R)/2` side signal in dBFS. The
 *   distance from [midRmsDbfs] is the measured stereo width.
 * @property interChannelCorrelation Correlation of the two channels in
 *   `[-1, 1]`: `1.0` for mono or dual-mono, `0.0` for uncorrelated channels,
 *   negative for out-of-phase content. `null` when a channel is digitally
 *   silent and the ratio has no meaning.
 * @property windowCount Analysis windows that contributed to the averages.
 * @property frameCount PCM frames that contributed to the energy measures.
 */
data class AudioAnalysisFeatures(
    val spectralRolloffHz: Double?,
    val spectralCentroidHz: Double?,
    val spectralSlope: Double?,
    val noiseFloorDbfs: Double?,
    val dcOffset: Double?,
    val leftRmsDbfs: Double?,
    val rightRmsDbfs: Double?,
    val midRmsDbfs: Double?,
    val sideRmsDbfs: Double?,
    val interChannelCorrelation: Double?,
    val windowCount: Int,
    val frameCount: Long,
) {
    /**
     * `true` when the measurement pass produced nothing usable.
     *
     * A caller should treat this exactly like a failed analysis and not persist
     * the row: it means no window ever reached the stats filters.
     */
    val isEmpty: Boolean
        get() = windowCount == 0 && frameCount == 0L
}
