package com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile

import com.androidexpert35.audiophilemusicplayer.data.playback.native_.AudioFormatInfo
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.DsdOutputMode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * Manages the reusable PCM and DSD scratch buffers for [BitPerfectPlaybackEngine].
 *
 * Both buffers grow as needed but are never shrunk — a single allocation covers
 * every subsequent track whose required capacity is ≤ the current capacity.
 *
 * [pcmBuffer] is allocated with [ByteBuffer.allocateDirect] so its backing
 * memory lives outside the JVM heap. `GetDirectBufferAddress` in the C layer
 * returns a stable raw pointer with no GC pinning overhead and no byte-level
 * copy across the JNI boundary.
 */
internal class BitPerfectTransportBuffers {

    /**
     * Reusable direct PCM scratch buffer shared across write-loop iterations.
     *
     * Written by the native decoder on each [com.androidexpert35.audiophilemusicplayer.data.playback.native_.FFmpegDecoder.readNextBuffer]
     * call and consumed by the active [AudiophileOutputSink.write] call.
     */
    var pcmBuffer: ByteBuffer = ByteBuffer
        .allocateDirect(DEFAULT_PCM_BUFFER_BYTES)
        .order(ByteOrder.nativeOrder())
        private set

    /**
     * Raw DSD scratch buffer shared across write-loop iterations.
     *
     * Written by the native decoder on each [com.androidexpert35.audiophilemusicplayer.data.playback.native_.FFmpegDecoder.readNextDsdBuffer]
     * call before DSD transport encoding is applied.
     */
    var dsdBuffer: ByteArray = ByteArray(DEFAULT_DSD_BUFFER_BYTES)
        private set

    /**
     * Ensures [pcmBuffer] and [dsdBuffer] are large enough to hold one decoded
     * chunk from a track described by [format] and [dsdContext].
     *
     * Buffers are never shrunk so this is effectively a one-time growth per
     * new maximum-size track encountered during the session.
     *
     * @param format Decoded audio format for the track about to be loaded.
     * @param dsdContext Active DSD transport context, or `null` for PCM tracks.
     */
    fun ensureFor(format: AudioFormatInfo, dsdContext: DsdPlaybackContext?) {
        if (dsdContext == null) {
            ensurePcmBufferFor(format)
            return
        }

        val desiredDsdBytes = max(DEFAULT_DSD_BUFFER_BYTES, format.bytesPerFrame * PCM_BUFFER_FRAME_CAPACITY * 2)
        if (dsdBuffer.size < desiredDsdBytes) {
            dsdBuffer = ByteArray(desiredDsdBytes)
        }

        val desiredTransportBytes = when (dsdContext.outputMode) {
            is DsdOutputMode.DoP -> desiredDsdBytes * MAX_DOP_EXPANSION_FACTOR
            else -> desiredDsdBytes
        }
        if (pcmBuffer.capacity() < desiredTransportBytes) {
            pcmBuffer = ByteBuffer
                .allocateDirect(desiredTransportBytes)
                .order(ByteOrder.nativeOrder())
        }
    }

    /**
     * Grows [pcmBuffer] when the current track demands a larger frame-aligned
     * capacity. The buffer is never shrunk — a single allocation covers every
     * subsequent track whose frame size is ≤ the current capacity.
     */
    private fun ensurePcmBufferFor(format: AudioFormatInfo) {
        val desired = max(DEFAULT_PCM_BUFFER_BYTES, format.bytesPerFrame * PCM_BUFFER_FRAME_CAPACITY)
        if (pcmBuffer.capacity() < desired) {
            pcmBuffer = ByteBuffer
                .allocateDirect(desired)
                .order(ByteOrder.nativeOrder())
        }
    }

    private companion object {
        /** 64 KiB — fits several HAL bursts and stays small enough for quick pause response. */
        const val DEFAULT_PCM_BUFFER_BYTES = 64 * 1024
        const val DEFAULT_DSD_BUFFER_BYTES = 64 * 1024

        /** Target frame capacity for the PCM scratch buffer (sized per track). */
        const val PCM_BUFFER_FRAME_CAPACITY = 4096
        const val MAX_DOP_EXPANSION_FACTOR = 2
    }
}

