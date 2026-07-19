package com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile

import android.media.AudioFormat
import com.androidexpert35.audiophilemusicplayer.data.playback.dsd.DoPEncoder
import com.androidexpert35.audiophilemusicplayer.data.playback.native_.AudioFormatInfo
import com.androidexpert35.audiophilemusicplayer.data.playback.native_.DsdPipelineInfo
import com.androidexpert35.audiophilemusicplayer.data.playback.native_.PipelinePathReport
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.DsdOutputMode
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.DsdRate
import java.nio.ByteBuffer

/**
 * Immutable DSD transport context retained for the currently loaded track.
 *
 * @property sourceRate Original DSD family read from the file.
 * @property effectiveRate Effective DSD family carried to the sink.
 * @property outputMode Negotiated DSD output mode.
 * @property sourceFormat Human-readable source DSD container label.
 * @property dopPcmRate PCM carrier rate used for DoP, or `null` for native DSD.
 * @property dopEncoder Stateful DoP encoder when DoP transport is active.
 */
internal data class DsdPlaybackContext(
    val sourceRate: DsdRate,
    val effectiveRate: DsdRate,
    val outputMode: DsdOutputMode,
    val sourceFormat: String,
    val dopPcmRate: Int?,
    val dopEncoder: DoPEncoder?,
)

/**
 * Mutable spill-state snapshot for DoP encoding across buffer boundaries.
 *
 * @property spillBuffer Retained DSD tail bytes that did not complete a DoP frame.
 * @property spillLength Number of valid bytes currently stored in [spillBuffer].
 */
internal data class DoPSpillState(
    val spillBuffer: ByteArray,
    val spillLength: Int,
)

/**
 * Result of preparing one DSD transport chunk for sink write.
 *
 * @property bytesToWrite Number of valid bytes now present in the PCM/direct transport buffer.
 * @property spillState Updated DoP spill-state for the next iteration.
 */
internal data class DsdTransportPreparation(
    val bytesToWrite: Int,
    val spillState: DoPSpillState,
)

/**
 * Creates the PCM carrier format used by the DoP fallback path.
 *
 * @param format Source DSD format information.
 * @param dopPcmRate PCM carrier rate in Hertz.
 * @return Transport-facing PCM format description for sink negotiation.
 */
internal fun createDoPTransportFormat(format: AudioFormatInfo, dopPcmRate: Int): AudioFormatInfo = format.copy(
    sampleRateHz = dopPcmRate,
    // DoP v1.1 is a 24-bit PCM carrier by specification. Using a 32-bit
    // container placed the 0x05 / 0xFA marker in the sign-bit position, which
    // caused the HAL to emit loud white noise whenever the payload was not
    // passed through bit-perfectly. The 24-bit packed encoding matches the DoP
    // spec exactly and is what every DoP-capable DAC expects.
    sourceBitDepth = 24,
    androidPcmEncoding = AudioFormat.ENCODING_PCM_24BIT_PACKED,
    bytesPerSample = 3,
    isDsd = false,
    dsdRate = null,
    dsdSourceFormat = null,
)

/**
 * Adds DSD pipeline metadata to an output [PipelinePathReport].
 *
 * @param pathReport Base sink path report.
 * @param dsdPlaybackContext Current DSD transport context, if any.
 * @return Path report enriched with DSD diagnostics.
 */
internal fun buildDsdPathReport(
    pathReport: PipelinePathReport,
    dsdPlaybackContext: DsdPlaybackContext?,
): PipelinePathReport = pathReport.copy(
    dsdPipelineInfo = dsdPlaybackContext?.let { context ->
        DsdPipelineInfo(
            sourceFormat = context.sourceFormat,
            sourceDsdRate = context.sourceRate,
            outputMode = context.outputMode,
            effectiveDsdRate = context.effectiveRate,
            dopPcmRate = context.dopPcmRate,
        )
    },
)

/**
 * Resolves the active playback clock for either PCM, native DSD, or DoP.
 *
 * @param format Current source format.
 * @param dsdPlaybackContext Active DSD context, if any.
 * @return Sample-rate clock in Hertz used for playback-head timing.
 */
internal fun playbackClockRateFor(
    format: AudioFormatInfo,
    dsdPlaybackContext: DsdPlaybackContext?,
): Int = dsdPlaybackContext?.dopPcmRate ?: format.sampleRateHz

/**
 * Prepares the next DSD chunk for sink write.
 *
 * @param dsdPlaybackContext Active DSD transport context.
 * @param dsdBuffer Raw DSD bytes just read from FFmpeg.
 * @param bytesRead Number of valid DSD bytes in [dsdBuffer].
 * @param transportBuffer Reusable direct output buffer owned by the engine.
 * @param spillState Current DoP spill-state.
 * @return Prepared output window size plus updated spill-state.
 */
internal fun prepareDsdTransportBuffer(
    dsdPlaybackContext: DsdPlaybackContext,
    dsdBuffer: ByteArray,
    bytesRead: Int,
    transportBuffer: ByteBuffer,
    spillState: DoPSpillState,
): DsdTransportPreparation = when (dsdPlaybackContext.outputMode) {
    is DsdOutputMode.NativeDsd -> {
        transportBuffer.clear()
        transportBuffer.put(dsdBuffer, 0, bytesRead)
        transportBuffer.flip()
        DsdTransportPreparation(
            bytesToWrite = bytesRead,
            spillState = spillState,
        )
    }

    is DsdOutputMode.DoP -> {
        val encodedChunk = encodeDoPChunk(
            dsdPlaybackContext = dsdPlaybackContext,
            dsdBuffer = dsdBuffer,
            bytesRead = bytesRead,
            spillState = spillState,
        )
        transportBuffer.clear()
        transportBuffer.put(encodedChunk.encodedBytes)
        transportBuffer.flip()
        DsdTransportPreparation(
            bytesToWrite = encodedChunk.encodedBytes.size,
            spillState = encodedChunk.spillState,
        )
    }

    DsdOutputMode.Unsupported -> DsdTransportPreparation(
        bytesToWrite = 0,
        spillState = spillState,
    )
}

private data class EncodedDoPChunk(
    val encodedBytes: ByteArray,
    val spillState: DoPSpillState,
)

private fun encodeDoPChunk(
    dsdPlaybackContext: DsdPlaybackContext,
    dsdBuffer: ByteArray,
    bytesRead: Int,
    spillState: DoPSpillState,
): EncodedDoPChunk {
    val combined = ByteArray(spillState.spillLength + bytesRead)
    if (spillState.spillLength > 0) {
        spillState.spillBuffer.copyInto(
            destination = combined,
            destinationOffset = 0,
            startIndex = 0,
            endIndex = spillState.spillLength,
        )
    }
    dsdBuffer.copyInto(
        destination = combined,
        destinationOffset = spillState.spillLength,
        startIndex = 0,
        endIndex = bytesRead,
    )

    val effectiveByteCount = if (
        dsdPlaybackContext.sourceRate == DsdRate.DSD256 &&
            dsdPlaybackContext.effectiveRate == DsdRate.DSD128
    ) {
        decimateDsd256ToDsd128InPlace(combined, combined.size)
    } else {
        combined.size
    }

    val frameCount = effectiveByteCount / DOP_FRAME_DSD_BYTES
    val consumedBytes = frameCount * DOP_FRAME_DSD_BYTES
    val remainingBytes = effectiveByteCount - consumedBytes
    val nextSpillBuffer = ByteArray(remainingBytes.coerceAtLeast(DOP_FRAME_DSD_BYTES))
    if (remainingBytes > 0) {
        combined.copyInto(
            destination = nextSpillBuffer,
            destinationOffset = 0,
            startIndex = consumedBytes,
            endIndex = effectiveByteCount,
        )
    }

    return EncodedDoPChunk(
        encodedBytes = dsdPlaybackContext.dopEncoder?.encode(combined, frameCount) ?: byteArrayOf(),
        spillState = DoPSpillState(
            spillBuffer = nextSpillBuffer,
            spillLength = remainingBytes,
        ),
    )
}

/**
 * Downsamples interleaved DSD256 byte-pairs to DSD128 in place.
 *
 * @param buffer Buffer containing interleaved stereo DSD bytes.
 * @param byteCount Number of valid bytes in [buffer].
 * @return Number of valid bytes after decimation.
 */
internal fun decimateDsd256ToDsd128InPlace(buffer: ByteArray, byteCount: Int): Int {
    var readOffset = 0
    var writeOffset = 0
    var keepPair = true

    // The fallback is intentionally explicit: when 705.6 kHz DoP is unavailable,
    // every other interleaved DSD byte pair is dropped so the remaining stream is
    // true DSD128 before the DoP encoder wraps it.
    while (readOffset + 1 < byteCount) {
        if (keepPair) {
            buffer[writeOffset] = buffer[readOffset]
            buffer[writeOffset + 1] = buffer[readOffset + 1]
            writeOffset += 2
        }
        keepPair = !keepPair
        readOffset += 2
    }
    return writeOffset
}

private const val DOP_FRAME_DSD_BYTES = 4

