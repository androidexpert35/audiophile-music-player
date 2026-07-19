package com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile

import android.util.Log
import com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile.SueStage.Companion.DEFAULT_CHUNK_FRAMES
import com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile.SueStage.Companion.FLUSH_HEADROOM_FRAMES
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Kotlin DSP stage wrapping [SueBridge] for the Sonic Upscaling Enhancer.
 *
 * ### Role in the pipeline
 * ```
 * [FFmpegDecoder] → PCM (source encoding) → [SueStage] → float32 PCM (effective SUE rate)
 *                                                ↓
 *                                        [SoxResamplerStage] → float32 PCM (target rate)
 *                                                ↓
 *                                        [AudiophileOutputSink]
 * ```
 * SUE is inserted **before** SoX so that the enhancement benefits from the
 * full-precision float32 signal — resampling before enhancement would
 * introduce mild band-limiting that reduces the exciter's effectiveness.
 *
 * ### Activation gate
 * When [isLossy] is `false` (FLAC, WAV, ALAC, DSD, …) the native context is
 * never created and every method is a no-op. This is the zero-cost lossless
 * bypass mandated by the spec.
 *
 * ### Output format
 * [SueBridge.nativeProcessBytes] always delivers `AV_SAMPLE_FMT_FLT`
 * (interleaved float32) regardless of the input encoding. Callers must
 * therefore treat the output encoding as [android.media.AudioFormat.ENCODING_PCM_FLOAT]
 * (`= 4`) when forwarding [outputBuffer] to [SoxResamplerStage.process].
 *
 * ### Buffer pre-allocation
 * [outputBuffer] is allocated once at construction for the maximum expected
 * chunk size plus [FLUSH_HEADROOM_FRAMES] of tail-drain headroom.
 * Reusing it across [process] calls keeps the write-loop path allocation-free.
 *
 * ### Thread safety
 * Not thread-safe. All calls **must** originate from the same audio thread —
 * [BitPerfectPlaybackEngine]'s `THREAD_PRIORITY_AUDIO` HandlerThread.
 *
 * @param inputEncoding                `AudioFormat.ENCODING_PCM_*` of the decoder output.
 * @param channelCount                 Interleaved channel count (typically 2).
 * @param sampleRateHz                 Source PCM sample rate in Hz.
 * @param targetSampleRateHz           Negotiated target carrier rate SUE should absorb when it
 *   owns the upsampling step. If the source rate is already at or above this target, SUE keeps
 *   the carrier unchanged and the internal resampler stage is skipped. Must equal `48 000`
 *   when [isForce48kResampleOnly] is `true`.
 * @param codecTier                    Codec efficiency tier from [SueCodecTier.from].
 * @param bitrateKbps                  Track encoded bitrate in kbps; `0` when unknown.
 * @param isLossy                      `true` for lossy sources (MP3, AAC, etc.); kept for
 *   backward compatibility on the lossy path.
 * @param isLosslessSource             `true` for FLAC / WAV / ALAC sources. When `true`,
 *   the Hi-Res Remaster engine is engaged instead of SUE; the native layer
 *   oversamples to 96 kHz internally and [outputSampleRateHz] reflects that.
 * @param downstreamHqResamplerActive  `true` when the libsoxr CHQ resampler is active on the
 *   path downstream of SUE, causing a one-step profile downgrade inside the
 *   native layer to compensate for the resampler's mild high-frequency accentuation.
 * @param specialFlags                 Codec-specific guardrails resolved in Kotlin and forwarded
 *   to the native graph builder.
 * @param replayGainDb                 ReplayGain track gain in dB from [FFmpegDecoder.getReplayGainDb].
 *   Forwarded to [SueBridge.nativeCreate] so [build_hires_remaster_chain] can drive its
 *   `volume` lavfi stage with the file's own gain metadata. Ignored by the lossy SUE and
 *   force-48k paths. Defaults to −3.0 dB when no ReplayGain tag was present in the file.
 * @param isForce48kResampleOnly       `true` when this stage should only resample the source to
 *   48 kHz using `aresample=resampler=soxr:precision=33:cutoff=0.91:osr=48000:
 *   dither_method=triangular_hp`, without any harmonic excitation or dynamic processing.
 *   Mutually exclusive with lossy SUE and lossless Hi-Res Remaster modes.
 */
class SueStage(
    val inputEncoding: Int,
    val channelCount: Int,
    val sampleRateHz: Int,
    val targetSampleRateHz: Int,
    val codecTier: SueCodecTier,
    val bitrateKbps: Int,
    val isLossy: Boolean,
    val isLosslessSource: Boolean = false,
    val downstreamHqResamplerActive: Boolean = false,
    val specialFlags: Int = 0,
    val replayGainDb: Float = DEFAULT_REPLAYGAIN_DB,
    val isForce48kResampleOnly: Boolean = false,
) : AutoCloseable {

    /**
     * Effective carrier rate emitted by this stage after DSP processing.
     *
     * - **Force-48k path**: always `48 000` Hz — the stage purely resamples to
     *   exactly this rate without any harmonic excitation.
     * - **Lossy SUE path**: `max(sampleRateHz, targetSampleRateHz)`.
     * - **Lossless Hi-Res Remaster path**: follows the integer multiplier rule
     *   applied by the C++ filter chain:
     *   - Source ≤ 48 000 Hz → `sampleRateHz × 2`
     *     (44 100 → 88 200 Hz, 48 000 → 96 000 Hz; stays in the same clock family)
     *   - Source > 48 000 Hz → `sampleRateHz` unchanged
     *     (already at or above the oversampling floor; no extra conversion step)
     */
    val outputSampleRateHz: Int = when {
        isForce48kResampleOnly -> targetSampleRateHz  // always 48_000 for this path
        !isLosslessSource -> maxOf(sampleRateHz, targetSampleRateHz)
        sampleRateHz <= 48_000 -> sampleRateHz * 2
        else -> sampleRateHz
    }

    private var nativeHandle: Long = 0L

    /** Most recent native initialisation failure message, if any. */
    val initFailureReason: String?
        get() = lastInitFailureReason

    private var lastInitFailureReason: String? = null

    /**
     * Pre-allocated direct [ByteBuffer] receiving float32 output from the filter graph.
     *
     * Sized conservatively at [DEFAULT_CHUNK_FRAMES] × [channelCount] × 4 bytes,
     * plus [FLUSH_HEADROOM_FRAMES] to absorb the filter-graph's tail drain at EOS.
     * Output from the filter graph is always interleaved float32.
     */
    val outputBuffer: ByteBuffer

    init {
        // Always attempt to create a native context; the C++ routing cop
        // returns 0L for bypass (isSueEnabled=false / isHiResEnabled=false /
        // BYPASS profile / unprovisioned build / isForce48kResampleOnly requires
        // libsoxr to be provisioned).
        nativeHandle = SueBridge.nativeCreate(
            isForce48kResampleOnly = isForce48kResampleOnly,
            codecTier    = codecTier.value,
            bitrateKbps  = bitrateKbps,
            sampleRateHz = sampleRateHz,
            targetSampleRateHz = targetSampleRateHz,
            channelCount = channelCount,
            inputEncoding = inputEncoding,
            downstreamHqResamplerActive = downstreamHqResamplerActive,
            specialFlags = specialFlags,
            // Mutually exclusive routing flags: exactly one is true per call
            // (force-48k overrides both SUE and Hi-Res when active).
            isSueEnabled = !isLosslessSource && !isForce48kResampleOnly,
            isHiResEnabled = isLosslessSource && !isForce48kResampleOnly,
            isLosslessSource = isLosslessSource,
            replayGainDb = replayGainDb,
        )
        if (nativeHandle == 0L) {
            lastInitFailureReason = SueBridge.nativeConsumeLastInitError().ifBlank { null }
            Log.w(
                TAG,
                buildString {
                    append("SUE/HiRes context unavailable — stage inactive for this track")
                    lastInitFailureReason?.let { append("; reason=").append(it) }
                }
            )
        }

        val capacityFrames = estimateOutputFrames(
            inputFrames = DEFAULT_CHUNK_FRAMES,
            inputRateHz = sampleRateHz,
            outputRateHz = outputSampleRateHz,
        ) + FLUSH_HEADROOM_FRAMES
        outputBuffer = ByteBuffer
            .allocateDirect(capacityFrames * channelCount * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
    }

    /**
     * `true` when the stage will actively apply DSP to the audio stream.
     *
     * Returns `false` when the native context is absent — either because
     * the intensity profile resolved to BYPASS (Opus at high bitrate), the
     * user disabled both engines, or the build is unprovisioned.
     */
    val isActive: Boolean get() = nativeHandle != 0L

    /**
     * Processes [inputFrames] PCM frames from [inputBuffer] through the SUE
     * filter graph.
     *
     * On return, [outputBuffer] is positioned at 0 with its limit set to the
     * number of valid **float32** output bytes, ready to be passed to
     * [SoxResamplerStage.process] with `inputEncoding=ENCODING_PCM_FLOAT`.
     *
     * The first few calls may return 0 while the filter graph primes its
     * internal resampler state. The caller should continue the write loop
     * without writing to the sink in that case.
     *
     * @param inputBuffer   Direct [ByteBuffer] from the FFmpeg decoder (position=0,
     *   limit=byteCount). The layout matches [inputEncoding].
     * @param inputFrames   PCM frames to consume (byte count ÷ bytesPerFrame).
     * @return Frames written to [outputBuffer], `0` during warm-up, or `-1` on error.
     */
    fun process(inputBuffer: ByteBuffer, inputFrames: Int): Int {
        if (!isActive) return -1
        outputBuffer.clear()
        val maxOutputFrames = outputBuffer.capacity() / (channelCount * Float.SIZE_BYTES)
        val framesOut = SueBridge.nativeProcessBytes(
            handle          = nativeHandle,
            inputBuffer     = inputBuffer,
            inputEncoding   = inputEncoding,
            inputFrames     = inputFrames,
            outputBuffer    = outputBuffer,
            outputMaxFrames = maxOutputFrames,
        )
        if (framesOut > 0) {
            outputBuffer.position(0)
            outputBuffer.limit(framesOut * channelCount * Float.SIZE_BYTES)
        }
        return framesOut
    }

    /**
     * Drains the filter graph's internal delay lines at end-of-track.
     *
     * Stateful filters (IIR equalisers and resamplers) may hold a
     * small number of frames in their pipelines. This method flushes them so
     * the final notes of each track are delivered to the DAC completely.
     *
     * On return, [outputBuffer] holds the tail in the same layout as [process].
     *
     * @return Tail frames written to [outputBuffer], or `0` if inactive.
     */
    fun flush(): Int {
        if (!isActive) return 0
        outputBuffer.clear()
        val maxOutputFrames = outputBuffer.capacity() / (channelCount * Float.SIZE_BYTES)
        val framesOut = SueBridge.nativeFlushBytes(nativeHandle, outputBuffer, maxOutputFrames)
        if (framesOut > 0) {
            outputBuffer.position(0)
            outputBuffer.limit(framesOut * channelCount * Float.SIZE_BYTES)
        }
        return framesOut
    }

    /**
     * Clears the filter graph's internal state after a seek.
     *
     * IIR equaliser history and resampler state are reset so that audio decoded
     * after the new position is uncontaminated
     * by the pre-seek signal.
     */
    fun reset() {
        if (isActive) SueBridge.nativeReset(nativeHandle)
    }

    /**
     * Destroys the native filter graph and frees all associated memory.
     *
     * Safe to call multiple times; subsequent calls are no-ops.
     * Must be called on the audio thread.
     */
    override fun close() {
        val h = nativeHandle
        if (h != 0L) {
            nativeHandle = 0L
            SueBridge.nativeDestroy(h)
        }
    }

    private companion object {

        const val TAG = "SueStage"

        /** Default chunk frame count used to size the pre-allocated output buffer. */
        private const val DEFAULT_CHUNK_FRAMES = 4_096


        /**
         * Estimates the output frame count for one SUE processing block.
         *
         * Adds 10 % headroom above the exact ratio so the reusable direct buffer
         * can safely hold both internal upsampling expansion and filter warm-up
         * variance without reallocating on the audio thread.
         */
        private fun estimateOutputFrames(inputFrames: Int, inputRateHz: Int, outputRateHz: Int): Int =
            ((inputFrames.toLong() * outputRateHz.coerceAtLeast(1) / inputRateHz.coerceAtLeast(1)) +
                inputRateHz.coerceAtLeast(1) / 100).toInt().coerceAtLeast(inputFrames)

        /**
         * Extra frames reserved for the filter-graph tail drain at end-of-track.
         * The IIR equalisers and resamplers hold at most a few hundred frames.
         * 2048 frames of
         * headroom provides ample safety margin.
         */
        private const val FLUSH_HEADROOM_FRAMES = 2_048

        /**
         * Default pre-expansion headroom used when the source file has no
         * REPLAYGAIN_TRACK_PEAK tag.
         *
         * −3.0 dB assumes the worst case (peak = 1.0) and creates exactly the
         * room the Hi-Res Remaster upward-expansion stage can add back — peaks
         * land at −0.5 dBFS after the compand curve. Must match
         * HIRES_REMASTER_DEFAULT_GAIN_DB in `sue_bridge.cpp` and
         * REMASTER_DEFAULT_GAIN_DB in `ffmpeg_bridge.cpp`.
         */
        const val DEFAULT_REPLAYGAIN_DB: Float = -3.0f
    }
}
