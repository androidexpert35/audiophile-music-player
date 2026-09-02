package com.androidexpert35.audiophilemusicplayer.domain.model.analysis

/**
 * Stationary ("Class S") signal measurements cached for one piece of audio.
 *
 * These describe what the source *is* rather than how loud a particular listen
 * was: how far up the spectrum it actually carries energy, how that energy is
 * tilted, and how the two channels relate. They are produced by one sampling
 * pass over a handful of windows, which is legitimate precisely because they do
 * not depend on where in the file they were measured.
 *
 * Every measured value is nullable. A statistic the measurement graph never
 * emitted (a digitally silent channel, a window too short to fill an FFT) is
 * reported as `null` rather than as a plausible-looking zero, and callers must
 * treat "absent" as "do not use", never as "0.0".
 *
 * @property spectralRolloffHz Frequency below which effectively all energy lies
 *   — the measured counterpart of the encoder low-pass a lossy source was
 *   produced with.
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
 * @property sideRmsDbfs RMS energy of the `(L-R)/2` side signal in dBFS. Its
 *   distance from [midRmsDbfs] is the measured stereo width.
 * @property interChannelCorrelation Correlation of the two channels in
 *   `[-1, 1]`: `1.0` for mono or dual-mono, `0.0` for uncorrelated channels,
 *   negative for out-of-phase content. `null` when a channel is digitally
 *   silent and the ratio has no meaning.
 * @property windowCount Analysis windows that contributed to the averages. Also
 *   the marker that this pass ran at all: a persisted row carries it, a row
 *   written by the integral pass alone does not.
 * @property frameCount PCM frames that contributed to the energy measures.
 */
data class StationaryAnalysis(
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
)
