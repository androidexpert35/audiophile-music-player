@file:Suppress("JniMissingFunction")

package com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile

import android.util.Log
import com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile.SueBridge.nativeCreate
import java.nio.ByteBuffer

/**
 * Kotlin JNI bridge declarations for the Sonic Upscaling Enhancer (SUE).
 *
 * All five native methods are implemented in `sue_bridge.cpp` inside the
 * `audiophile_native` shared library — the same `.so` already loaded by
 * [com.androidexpert35.audiophilemusicplayer.data.playback.native_.FFmpegDecoder].
 *
 * SUE applies an audiophile DSP pipeline using the FFmpeg `libavfilter` lavfi
 * graph API: conditional pre-exciter upsampling to the negotiated target
 * carrier, harmonic excitation, gentle high-band contouring, guarded stereo
 * widening, a soft low-pass, and final TPDF dithering at the active pipeline
 * carrier rate. The pipeline is
 * activated only for lossy-compressed sources; for lossless or DSD tracks
 * [nativeCreate] is never called and the stage costs zero overhead.
 *
 * When FFmpeg is not provisioned (stub build), every function returns a
 * neutral value: [nativeCreate] returns `0L` and [SueStage.isActive] is
 * therefore `false`, so the pipeline silently falls back to passthrough.
 *
 * ### Thread-safety
 * Every call on a given handle MUST be made from the same audio thread —
 * [BitPerfectPlaybackEngine]'s dedicated `THREAD_PRIORITY_AUDIO` HandlerThread.
 */
object SueBridge {

    private const val TAG = "SueBridge"

    /**
     * Creates a DSP filter graph context for one track load.
     *
     * Acts as the **routing cop** for the dual-engine architecture:
     *
     * - **Force-48k path** (`isForce48kResampleOnly = true`): builds a minimal
     *   `aresample=resampler=soxr:precision=33:cutoff=0.91:osr=48000:
     *   dither_method=triangular_hp` filter graph. All other engine flags are
     *   ignored. This path is activated when no USB DAC is connected and the
     *   source rate is not already 48 kHz, so the app performs the resample
     *   itself via libsoxr VHQ instead of leaving it to AudioFlinger's
     *   lower-quality internal resampler.
     * - **Lossy path** (`isLosslessSource = false`, `isForce48kResampleOnly = false`):
     *   when `isSueEnabled` is `true`, builds the existing SUE harmonic-excitation
     *   graph; otherwise returns `0L` (transparent bypass).
     * - **Lossless path** (`isLosslessSource = true`): when `isHiResEnabled` is `true`,
     *   builds the Hi-Res Dynamic Remaster graph (96 kHz oversampling, upward dynamic
     *   expansion, triangular HP dithering); otherwise returns `0L` (transparent bypass).
     *
     * The three engine modes are **mutually exclusive** per call.
     *
     * @param isForce48kResampleOnly   `true` to build a minimal libsoxr passthrough
     *   resampler that converts the source to exactly 48 kHz without any harmonic
     *   excitation or dynamic processing. When `true`, all other engine flags are
     *   ignored.
     * @param codecTier                    Efficiency tier (0=TIER_LOW … 3=TIER_ULTRA).
     *   Ignored when [isLosslessSource] or [isForce48kResampleOnly] is `true`.
     * @param bitrateKbps                  Track encoded bitrate in kbps; `0` when unknown.
     *   Ignored when [isLosslessSource] or [isForce48kResampleOnly] is `true`.
     * @param sampleRateHz                 Source PCM sample rate in Hz.
     * @param targetSampleRateHz           Negotiated target carrier rate for the lossy
     *   SUE path. Ignored by the Hi-Res Remaster path which always upsamples to 96 kHz.
     *   Must be `48 000` when [isForce48kResampleOnly] is `true`.
     * @param channelCount                 Interleaved channel count (typically 2).
     * @param inputEncoding                `AudioFormat.ENCODING_PCM_*` of the decoder output.
     * @param downstreamHqResamplerActive  `true` when libsoxr CHQ is active downstream.
     *   Triggers a one-step lossy-profile downgrade. Ignored for the lossless and
     *   force-48k paths.
     * @param specialFlags                 Codec-specific AAC-HE guardrails. Ignored for
     *   the lossless and force-48k paths.
     * @param isSueEnabled                 `true` to activate the SUE lossy engine when
     *   [isLosslessSource] is `false` and [isForce48kResampleOnly] is `false`.
     * @param isHiResEnabled               `true` to activate the Hi-Res Remaster engine
     *   when [isLosslessSource] is `true`. Ignored for the lossy and force-48k paths.
     * @param isLosslessSource             `true` for FLAC / WAV / ALAC / bit-perfect
     *   sources; `false` for MP3 / AAC / OGG / Opus / WMA. Ignored when
     *   [isForce48kResampleOnly] is `true`.
     * @param replayGainDb                 Peak-derived pre-expansion headroom gain in
     *   dB read from the file's metadata by [FFmpegDecoder.getReplayGainDb]. Consumed
     *   exclusively by the Hi-Res Dynamic Remaster path inside
     *   `build_hires_remaster_chain` to drive the `volume` lavfi stage; clamped
     *   natively to [−6, 0]. Ignored by the lossy SUE and force-48k paths.
     *   Pass `SueStage.DEFAULT_REPLAYGAIN_DB` (−3.0) when no tag was found.
     * @return Opaque context handle, or `0L` on bypass / failure / unprovisioned build.
     */
    @JvmStatic external fun nativeCreate(
        isForce48kResampleOnly: Boolean,
        codecTier: Int,
        bitrateKbps: Int,
        sampleRateHz: Int,
        targetSampleRateHz: Int,
        channelCount: Int,
        inputEncoding: Int,
        downstreamHqResamplerActive: Boolean,
        specialFlags: Int,
        isSueEnabled: Boolean,
        isHiResEnabled: Boolean,
        isLosslessSource: Boolean,
        replayGainDb: Float,
    ): Long

    /**
     * Consumes the most recent SUE initialisation failure reason.
     *
     * Used when [nativeCreate] returns `0L` so Kotlin can log a precise reason
     * instead of assuming the feature is absent from the build.
     *
     * @return Best-effort diagnostic text, or an empty string when no detail was captured.
     */
    @JvmStatic external fun nativeConsumeLastInitError(): String

    /**
     * Processes [inputFrames] PCM frames from [inputBuffer] through the SUE
     * filter graph and writes interleaved float32 output to [outputBuffer].
     *
     * The filter graph converts the input to float internally if necessary and
     * always delivers `AV_SAMPLE_FMT_FLT` (interleaved float32) to the output
     * buffer. No allocation occurs in steady state — `AVFilterGraph` uses an
     * internal memory pool after the initial warm-up.
     *
     * @param handle          Handle from [nativeCreate].
     * @param inputBuffer     Direct [ByteBuffer] with raw decoded PCM (position=0).
     * @param inputEncoding   `AudioFormat.ENCODING_PCM_*` constant for [inputBuffer].
     * @param inputFrames     Frames to consume (NOT byte count).
     * @param outputBuffer    Direct [ByteBuffer] receiving float32 output.
     * @param outputMaxFrames Capacity of [outputBuffer] in frames.
     * @return Frames written to [outputBuffer], `0` during filter warm-up, or
     *   `-1` on native error.
     */
    @JvmStatic external fun nativeProcessBytes(
        handle: Long,
        inputBuffer: ByteBuffer,
        inputEncoding: Int,
        inputFrames: Int,
        outputBuffer: ByteBuffer,
        outputMaxFrames: Int,
    ): Int

    /**
     * Flushes the filter graph's internal delay lines at end-of-track.
     *
     * Sends an EOF signal to the `abuffer` source, causing pending frames
     * buffered by stateful filters (IIR equalisers and resamplers) to be drained
     * to the `abuffersink` and written to [outputBuffer].
     *
     * @param handle          Handle from [nativeCreate].
     * @param outputBuffer    Direct [ByteBuffer] receiving tail float32 frames.
     * @param outputMaxFrames Capacity of [outputBuffer] in frames.
     * @return Tail frames written, or `0` if no tail available.
     */
    @JvmStatic external fun nativeFlushBytes(
        handle: Long,
        outputBuffer: ByteBuffer,
        outputMaxFrames: Int,
    ): Int

    /**
     * Rebuilds the filter graph to clear internal state after a seek.
     *
     * IIR equaliser coefficients and resampler state are cleared so the
     * post-seek stream starts from a clean filter
     * history, preventing pre-seek content from bleeding through.
     *
     * @param handle Handle from [nativeCreate].
     */
    @JvmStatic external fun nativeReset(handle: Long)

    /**
     * Destroys the native SUE context and frees all associated memory.
     *
     * @param handle Handle from [nativeCreate]. Safe to pass `0L`.
     */
    @JvmStatic external fun nativeDestroy(handle: Long)

    init {
        // audiophile_native is already loaded by FFmpegDecoder; a second
        // System.loadLibrary call is a documented no-op when the library name
        // matches an already-loaded DSO.
        runCatching { System.loadLibrary("audiophile_native") }
            .onFailure { Log.e(TAG, "Failed to load audiophile_native — SUE bridge inactive", it) }
    }
}

