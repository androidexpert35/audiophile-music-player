package com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile

import com.androidexpert35.audiophilemusicplayer.data.playback.native_.PipelinePathReport
import java.nio.ByteBuffer

/**
 * Output contract used by the FFmpeg-backed audiophile engine.
 *
 * Implementations may route decoded PCM either through a directly claimed USB
 * DAC or through the platform `AudioTrack` stack when no external DAC is
 * available. The engine depends on this abstraction so FFmpeg decoding remains
 * available regardless of USB state.
 *
 * @property pathReport Diagnostic description of the negotiated output path.
 * @property playbackHeadClockRateHz Optional sink-owned clock for interpreting
 *   [getPlaybackHeadPositionFrames], or `null` when the engine pipeline owns it.
 */
interface AudiophileOutputSink : AutoCloseable {

    /** Diagnostic description of the negotiated output path. */
    val pathReport: PipelinePathReport

    /**
     * Clock rate matching the frame unit returned by [getPlaybackHeadPositionFrames].
     *
     * A sink overrides this when its playhead unit differs from the nominal
     * pipeline carrier. Direct libusb DSD, for example, reports one-bit DSD
     * frames even when its negotiated fallback transport is DoP.
     */
    val playbackHeadClockRateHz: Int?
        get() = null

    /** Starts the sink so decoded PCM can begin flowing to the output. */
    fun play()

    /** Pauses output without discarding already queued data. */
    fun pause()

    /** Clears any buffered audio waiting to be rendered. */
    fun flush()

    /** Stops rendering and drains or releases any in-flight output work. */
    fun stop()

    /**
     * Writes decoded PCM from [buffer].
     *
     * @param buffer Direct source buffer containing interleaved PCM frames.
     * @param size Number of valid bytes available from the current buffer window.
     * @return Total bytes written successfully, or a negative error code.
     */
    fun write(buffer: ByteBuffer, size: Int): Int

    /** @return Current playback-head position in the sink's rendered frame unit. */
    fun getPlaybackHeadPositionFrames(): Long

    /** Releases any platform or USB resources held by the sink. */
    override fun close()
}
