package com.androidexpert35.audiophilemusicplayer.data.playback.native_

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log

/**
 * Builds an [AudioTrack] for audiophile playback using a three-rung fallback chain.
 *
 * 1. **Attempt 1** — `FLAG_DIRECT` + native encoding (source bit-depth, source sample
 *    rate). This is the only truly bit-transparent option.
 * 2. **Attempt 2** — `FLAG_DIRECT` + `PCM_FLOAT`. Retries the direct path at float
 *    encoding for decoders whose native integer format the HAL refused.
 * 3. **Attempt 3** — Standard mixer path at the native encoding. Bit-perfect is no
 *    longer guaranteed but volume processing remains under our control.
 *    **Skipped when [requireDirectOutput] is `true`** — callers transporting a
 *    fragile DSD-over-PCM (DoP) bitstream must pass `true` here so the engine
 *    fails fast instead of routing through the software mixer where volume
 *    processing would corrupt the DoP marker pattern.
 *
 * @param context Application context used for [AudioManager] property queries.
 * @param format Decoded audio format produced by [FFmpegDecoder].
 * @param bufferMultiplier Multiplier applied to `AudioTrack.getMinBufferSize()`.
 * @param attributes Audio attributes describing the playback use-case.
 * @param requireDirectOutput When `true`, the standard-mixer fallback (Attempt 3)
 *   is suppressed. If Attempts 1 and 2 both fail, the function throws
 *   [IllegalStateException] so the caller can degrade to an alternative path.
 * @return A pair of the constructed [AudioTrack] and its [PipelinePathReport].
 * @throws IllegalStateException when all eligible rungs fail to produce an initialized
 *   [AudioTrack].
 */
internal fun buildAudioTrackWithFallback(
    context: Context,
    format: AudioFormatInfo,
    bufferMultiplier: Int,
    attributes: AudioAttributes,
    requireDirectOutput: Boolean = false,
): Pair<AudioTrack, PipelinePathReport> {
    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val nativeRate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0
    val framesPerBuffer = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 0
    val channelMask = channelMaskForCount(format.channelCount)

    // Attempt 1 — FLAG_DIRECT with the decoder's native encoding.
    tryBuildAudioTrack(
        encoding = format.androidPcmEncoding,
        sampleRateHz = format.sampleRateHz,
        channelMask = channelMask,
        bufferMultiplier = bufferMultiplier,
        attributes = attributes,
        useDirectFlag = true,
    )?.let { (track, bufferFrames) ->
        return track to buildAudioTrackPathReport(
            track = track, format = format, encoding = format.androidPcmEncoding,
            channelMask = channelMask, bufferFrames = bufferFrames,
            nativeRate = nativeRate, framesPerBuffer = framesPerBuffer,
            usedDirectFlag = true, usedFloatFallback = false,
        )
    }

    // Attempt 2 — FLAG_DIRECT + PCM_FLOAT (integer decoders the HAL refused).
    // Skipped when requireDirectOutput=true: for DoP transports the encoding must
    // match exactly what the DoPEncoder produces (PCM_24BIT_PACKED). Writing
    // PCM_24BIT_PACKED frames into a PCM_FLOAT AudioTrack misaligns sample
    // boundaries (6 bytes/frame vs 8 bytes/frame stereo) and corrupts the output.
    if (!requireDirectOutput && format.androidPcmEncoding != AudioFormat.ENCODING_PCM_FLOAT) {
        tryBuildAudioTrack(
            encoding = AudioFormat.ENCODING_PCM_FLOAT,
            sampleRateHz = format.sampleRateHz,
            channelMask = channelMask,
            bufferMultiplier = bufferMultiplier,
            attributes = attributes,
            useDirectFlag = true,
        )?.let { (track, bufferFrames) ->
            Log.w(TAG, "FLAG_DIRECT accepted only at PCM_FLOAT — telemetry will show fallback")
            return track to buildAudioTrackPathReport(
                track = track, format = format, encoding = AudioFormat.ENCODING_PCM_FLOAT,
                channelMask = channelMask, bufferFrames = bufferFrames,
                nativeRate = nativeRate, framesPerBuffer = framesPerBuffer,
                usedDirectFlag = true, usedFloatFallback = true,
            )
        }
    }

    // Attempt 3 — standard mixer path. Last resort; bit-perfect no longer guaranteed.
    // Skipped when requireDirectOutput=true (DoP or other streams that must not pass
    // through the software mixer — routing them here would corrupt the signal).
    if (requireDirectOutput) {
        error(
            "FLAG_DIRECT required but all direct rungs refused at " +
                "${format.sampleRateHz} Hz ${format.channelCount}ch " +
                "encoding=${format.androidPcmEncoding}. Cannot route DoP through the mixer."
        )
    }
    val (track, bufferFrames) = tryBuildAudioTrack(
        encoding = format.androidPcmEncoding,
        sampleRateHz = format.sampleRateHz,
        channelMask = channelMask,
        bufferMultiplier = bufferMultiplier,
        attributes = attributes,
        useDirectFlag = false,
    ) ?: error(
        "AudioTrack could not be built for ${format.sampleRateHz}Hz " +
            "${format.channelCount}ch encoding=${format.androidPcmEncoding}"
    )
    Log.w(TAG, "All FLAG_DIRECT rungs refused — falling through to standard mixer path")
    return track to buildAudioTrackPathReport(
        track = track, format = format, encoding = format.androidPcmEncoding,
        channelMask = channelMask, bufferFrames = bufferFrames,
        nativeRate = nativeRate, framesPerBuffer = framesPerBuffer,
        usedDirectFlag = false, usedFloatFallback = false,
    )
}

/**
 * Attempts to construct an [AudioTrack] with the given shape.
 *
 * Returns `null` on any failure (exception or `STATE_UNINITIALIZED` result),
 * leaving the caller free to try the next fallback rung.
 *
 * @param encoding Target PCM encoding constant from [AudioFormat].
 * @param sampleRateHz Sample rate in Hz.
 * @param channelMask Channel mask from [AudioFormat].
 * @param bufferMultiplier Multiplier applied to `getMinBufferSize()`.
 * @param attributes Audio attributes for the track.
 * @param useDirectFlag Whether to request `AUDIO_OUTPUT_FLAG_DIRECT` via reflection.
 * @return A pair of the constructed [AudioTrack] and its buffer size in frames,
 *   or `null` when the attempt fails.
 */
internal fun tryBuildAudioTrack(
    encoding: Int,
    sampleRateHz: Int,
    channelMask: Int,
    bufferMultiplier: Int,
    attributes: AudioAttributes,
    useDirectFlag: Boolean,
): Pair<AudioTrack, Int>? {
    val minBytes = AudioTrack.getMinBufferSize(sampleRateHz, channelMask, encoding)
    if (minBytes <= 0) return null
    val bufferBytes = minBytes * bufferMultiplier

    val audioFormat = AudioFormat.Builder()
        .setEncoding(encoding)
        .setSampleRate(sampleRateHz)
        .setChannelMask(channelMask)
        .build()

    return runCatching {
        val builder = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferBytes)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_NONE)
            .setTransferMode(AudioTrack.MODE_STREAM)

        if (useDirectFlag) {
            applyDirectOutputFlag(builder)
        }

        val track = builder.build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            return@runCatching null
        }

        val bytesPerFrame = bytesPerAudioFrame(encoding, channelMask)
        val bufferFrames = if (bytesPerFrame > 0) bufferBytes / bytesPerFrame else 0
        track to bufferFrames
    }.onFailure { t ->
        Log.d(TAG, "AudioTrack.Builder refused direct=$useDirectFlag enc=$encoding: ${t.message}")
    }.getOrNull()
}

/**
 * Applies `AUDIO_OUTPUT_FLAG_DIRECT` to [builder] via reflection.
 *
 * `AudioTrack.Builder.setFlags(int)` is a `@hide` platform API — the
 * public SDK does not expose it, but the method has existed on every
 * Android version since API 23. Calling it through reflection asks
 * the platform to route the track through the direct-output path
 * (bypassing the Flinger mixer) when the HAL supports it.
 *
 * Failure modes are silently swallowed so the caller falls through
 * to the non-direct path automatically.
 *
 * @param builder The [AudioTrack.Builder] to patch.
 */
internal fun applyDirectOutputFlag(builder: AudioTrack.Builder) {
    runCatching {
        val method = AudioTrack.Builder::class.java.getMethod("setFlags", Int::class.javaPrimitiveType)
        method.invoke(builder, AUDIO_OUTPUT_FLAG_DIRECT)
    }.onFailure { t ->
        Log.d(TAG, "setFlags reflection failed (hidden-API policy?): ${t.message}")
    }
}

/**
 * Builds a [PipelinePathReport] from a successfully constructed [AudioTrack]
 * and its negotiated format parameters.
 *
 * @param track The successfully built [AudioTrack].
 * @param format Source audio format (pre-negotiation).
 * @param encoding Actual PCM encoding used by the track.
 * @param channelMask Channel mask used by the track.
 * @param bufferFrames Buffer size in audio frames.
 * @param nativeRate Device native output sample rate.
 * @param framesPerBuffer Device frames-per-buffer property.
 * @param usedDirectFlag Whether the direct-output flag was requested.
 * @param usedFloatFallback Whether PCM_FLOAT was substituted for the source encoding.
 * @return A populated [PipelinePathReport] describing the negotiated output path.
 */
internal fun buildAudioTrackPathReport(
    track: AudioTrack,
    format: AudioFormatInfo,
    encoding: Int,
    channelMask: Int,
    bufferFrames: Int,
    nativeRate: Int,
    framesPerBuffer: Int,
    usedDirectFlag: Boolean,
    usedFloatFallback: Boolean,
): PipelinePathReport {
    val routed: AudioDeviceInfo? = track.routedDevice
    return PipelinePathReport(
        usedDirectFlag = usedDirectFlag,
        usedFloatFallback = usedFloatFallback,
        encoding = encoding,
        sampleRateHz = format.sampleRateHz,
        channelMask = channelMask,
        bufferFrames = bufferFrames,
        nativeOutputSampleRateHz = nativeRate,
        framesPerBuffer = framesPerBuffer,
        routedDeviceType = routed?.type ?: 0,
        routedDeviceName = routed?.productName?.toString(),
        audioSessionId = track.audioSessionId,
    )
}

/**
 * Maps a channel count to the corresponding [AudioFormat] channel mask constant.
 *
 * Falls back to stereo for any channel count not listed below.
 *
 * @param channelCount Number of audio channels.
 * @return An [AudioFormat] `CHANNEL_OUT_*` mask.
 */
internal fun channelMaskForCount(channelCount: Int): Int = when (channelCount) {
    1 -> AudioFormat.CHANNEL_OUT_MONO
    2 -> AudioFormat.CHANNEL_OUT_STEREO
    3 -> AudioFormat.CHANNEL_OUT_STEREO or AudioFormat.CHANNEL_OUT_FRONT_CENTER
    4 -> AudioFormat.CHANNEL_OUT_QUAD
    6 -> AudioFormat.CHANNEL_OUT_5POINT1
    8 -> AudioFormat.CHANNEL_OUT_7POINT1_SURROUND
    else -> AudioFormat.CHANNEL_OUT_STEREO
}

/**
 * Computes the number of bytes per interleaved audio frame for a given
 * [encoding] and [channelMask] combination.
 *
 * @param encoding PCM encoding constant from [AudioFormat].
 * @param channelMask Channel mask from [AudioFormat].
 * @return Bytes per interleaved frame, or `0` for unrecognised encodings.
 */
internal fun bytesPerAudioFrame(encoding: Int, channelMask: Int): Int {
    val channelCount = Integer.bitCount(channelMask).coerceAtLeast(1)
    val bytesPerSample = when (encoding) {
        AudioFormat.ENCODING_PCM_8BIT -> 1
        AudioFormat.ENCODING_PCM_16BIT -> 2
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
        AudioFormat.ENCODING_PCM_32BIT,
        AudioFormat.ENCODING_PCM_FLOAT -> 4
        else -> 0
    }
    return bytesPerSample * channelCount
}

private const val TAG = "AudioTrackSink"

/**
 * `AUDIO_OUTPUT_FLAG_DIRECT` — mirrored from the Android source tree
 * (not part of the public SDK but a stable platform value since API 23).
 */
private const val AUDIO_OUTPUT_FLAG_DIRECT: Int = 0x1

