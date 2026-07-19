@file:Suppress("JniMissingFunction")

package com.androidexpert35.audiophilemusicplayer.data.playback.native_

import android.util.Log
import com.androidexpert35.audiophilemusicplayer.data.playback.native_.FFmpegDecoder.Companion.READ_RECOVERABLE_ERROR
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioCodec
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.DsdRate
import dalvik.annotation.optimization.FastNative
import java.nio.ByteBuffer

/**
 * Kotlin facade over the native FFmpeg JNI decoder.
 *
 * ### Ownership & threading
 *
 * Each instance owns exactly one native session (an opaque `jlong` handle).
 * The native layer performs **no internal locking** — every method on a given
 * instance MUST be called from the same thread. In the Audiophile pipeline
 * that thread is [com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile.BitPerfectPlaybackEngine]'s dedicated audio thread
 * (`Process.THREAD_PRIORITY_AUDIO`).
 *
 * ### Lifecycle
 *
 * ```
 *   val decoder = FFmpegDecoder()
 *   val format  = decoder.open(absolutePath)      // throws on failure
 *   while (true) {
 *       val n = decoder.readNextBuffer(buf)
 *       when {
 *           n >  0 -> sink.write(buf, n)
 *           n == 0 -> break                       // end of stream
 *           n <  0 -> /* recoverable decode blip — retry */
 *       }
 *   }
 *   decoder.close()
 * ```
 *
 * [close] is idempotent and must be called on error paths as well.
 */
class FFmpegDecoder {

    /** Native session handle, or `0` once [close] has been invoked. */
    @Volatile
    private var handle: Long = 0L

    /** Populated by [open]; immutable for the decoder's lifetime. */
    private var cachedFormat: AudioFormatInfo? = null

    /**
     * Opens [absolutePath] through `avformat_open_input`, picks the best audio
     * stream, and selects the highest-quality packed Android PCM carrier the
     * decoder can deliver without quality loss.
     *
     * The returned [AudioFormatInfo] reflects the exact shape of the bytes that
     * the active read path will subsequently produce: PCM via [readNextBuffer]
     * or raw MSB-first DSD via [readNextDsdBuffer].
     *
     * @param absolutePath A filesystem path understood by FFmpeg. Pass a real
     *   filesystem path when available; for `content://` URIs resolve them to
     *   a file descriptor via `ContentResolver.openFileDescriptor` and use
     *   `"/proc/self/fd/<fd>"`.
     * @param forcePcm When `true`, DSD source files are decoded to float PCM
     *   via FFmpeg's DSD decoder instead of being exposed as raw DSD bytes.
     *   Callers should pass `true` whenever the active output cannot sustain a
     *   bit-perfect DoP carrier (Bluetooth A2DP, Android mixer resampling,
     *   built-in speakers, etc.) — otherwise the DoP marker pattern gets
     *   mangled by the HAL and the user hears white noise with faint music.
     *   When `true` and the source is DSD, the native layer applies a lavfi
     *   pipeline (volume restore → LPF → limiter → soxr VHQ aresample to
     *   88 200 Hz) entirely inside FFmpeg. The returned [AudioFormatInfo] will
     *   have [AudioFormatInfo.sampleRateHz] == 88 200 and
     *   [AudioFormatInfo.isResampledDsd] == `true`.
     * @return Immutable decoded format description.
     * @throws FFmpegDecoderException on any native failure.
     * @throws IllegalStateException if [open] was already called.
     */
    fun open(
        absolutePath: String,
        forcePcm: Boolean = false,
    ): AudioFormatInfo {
        check(handle == 0L) { "FFmpegDecoder already open" }
        Log.i(
            PATH_TAG,
            "Opening FFmpeg decoder for source=${sanitizeSource(absolutePath)} forcePcm=$forcePcm"
        )
        val h = nativeOpen(absolutePath, forcePcm)
        if (h == 0L) {
            // Native layer must have already thrown FFmpegDecoderException —
            // but defend against JNI quirks by throwing a generic failure here.
            throw FFmpegDecoderException("nativeOpen returned a null handle")
        }
        handle = h
        val isDsd = nativeIsDsd(h)
        val isResampledDsd = nativeIsResampledDsd(h)
        val dsdRate: DsdRate? = nativeGetDsdRate(h).let(DsdRate::fromMultiplier)
            ?: if (isDsd) DsdRate.fromSampleRate(nativeGetSampleRate(h) * DSD_FFMPEG_BYTE_SHIFT)
               else null
        val codecName = nativeGetCodecName(h)
        val codecProfileName = nativeGetCodecProfileName(h)
        val codec = if ((isDsd || isResampledDsd) && dsdRate != null) {
            AudioCodec.fromDsdRate(dsdRate)
        } else {
            AudioCodec.fromCodecName(codecName)
        }
        val info = AudioFormatInfo(
            sampleRateHz = nativeGetSampleRate(h),
            channelCount = nativeGetChannelCount(h),
            sourceBitDepth = nativeGetSourceBitDepth(h),
            androidPcmEncoding = nativeGetAndroidPcmEncoding(h),
            bytesPerSample = nativeGetBytesPerSample(h),
            durationMs = nativeGetDurationUs(h) / MICROSECONDS_PER_MILLISECOND,
            bitrateKbps = (nativeGetBitrateBps(h) / BITS_PER_KILOBIT).toInt(),
            codec = codec,
            codecName = codecName,
            codecProfileName = codecProfileName,
            isDsd = isDsd,
            dsdRate = dsdRate,
            dsdSourceFormat = nativeGetContainerName(h).ifBlank { null },
            isResampledDsd = isResampledDsd,
        )
        cachedFormat = info
        Log.i(
            PATH_TAG,
            "FFmpeg decoder ready codec=${info.codec.displayName} sampleRate=${info.sampleRateHz}Hz " +
                "channels=${info.channelCount} sourceBitDepth=${info.sourceBitDepth} " +
                "pcmEncoding=${info.androidPcmEncoding} bitrate=${info.bitrateKbps}kbps " +
                "codecProfile=${info.codecProfileName.ifBlank { "n/a" }} " +
                "isDsd=${info.isDsd} dsdRate=${info.dsdRate?.displayName ?: "n/a"}"
        )
        return info
    }

    /**
     * Returns the [AudioFormatInfo] captured during [open].
     *
     * @throws IllegalStateException when called before [open] or after [close].
     */
    fun getFormat(): AudioFormatInfo =
        cachedFormat ?: error("FFmpegDecoder.getFormat() called before open()")

    /**
     * Reads the next chunk of packed, interleaved PCM into [buffer].
     *
     * [buffer] MUST be a direct `ByteBuffer` allocated with
     * [ByteBuffer.allocateDirect]. The native layer obtains a raw C pointer
     * via `GetDirectBufferAddress` — no JVM ↔ native copy is performed. The
     * caller is responsible for flipping / clearing the buffer before feeding
     * it to the audio sink.
     *
     * @param buffer Caller-owned **direct** output buffer. Capacity should be
     *   a whole multiple of [AudioFormatInfo.bytesPerFrame] for clean frame
     *   alignment.
     * @return Bytes actually written into [buffer] starting at position 0.
     *   Interpretation:
     *   - `> 0`  — normal data; the valid window is `[0, returnValue)`.
     *   - `0`    — end of stream (no further data will ever arrive).
     *   - `< 0`  — recoverable decode error
     *              ([READ_RECOVERABLE_ERROR]); caller may retry immediately.
     * @throws IllegalStateException when called after [close].
     */
    fun readNextBuffer(buffer: ByteBuffer): Int {
        val h = handle
        check(h != 0L) { "FFmpegDecoder used after close()" }
        require(buffer.isDirect) {
            "FFmpegDecoder.readNextBuffer requires a direct ByteBuffer"
        }
        return nativeReadNextBuffer(h, buffer, buffer.capacity())
    }

    /**
     * Reads the next chunk of raw DSD bytes into [buffer].
     *
     * The returned bytes are already normalized to MSB-first bit order and, for
     * planar sources, interleaved as stereo byte pairs. This method is valid
     * only when [getFormat] reports `isDsd == true`.
     *
     * @param buffer Caller-owned byte array receiving raw DSD transport bytes.
     * @return Bytes written into [buffer], `0` at end of stream, or
     *   [READ_RECOVERABLE_ERROR] for a recoverable read failure.
     * @throws IllegalStateException when called after [close].
     */
    fun readNextDsdBuffer(buffer: ByteArray): Int {
        val h = handle
        check(h != 0L) { "FFmpegDecoder used after close()" }
        return nativeReadNextDsdBuffer(h, buffer)
    }

    /**
     * Seeks to [positionMs] by converting to the audio stream's native time
     * base and issuing `av_seek_frame(..., AVSEEK_FLAG_BACKWARD)`. The decoder
     * is flushed and any cached spill is dropped. Raw DSD sessions then trim
     * the leading bytes between the demuxer packet boundary and the requested
     * timestamp so playback starts at sample precision.
     *
     * @param positionMs Non-negative seek target in milliseconds.
     * @return `true` on success, `false` on native failure.
     * @throws FFmpegDecoderException when FFmpeg reports a seek error.
     */
    fun seekTo(positionMs: Long): Boolean {
        val h = handle
        check(h != 0L) { "FFmpegDecoder used after close()" }
        return nativeSeek(h, positionMs.coerceAtLeast(0L) * MICROSECONDS_PER_MILLISECOND)
    }

    /**
     * Returns the ReplayGain track gain (in dB) for the Hi-Res Dynamic Remaster
     * path.
     *
     * Native metadata lookup is performed lazily only when this method is called.
     * The playback engine invokes it exclusively when Hi-Res Dynamic Remaster is
     * the active DSP owner for a CD-quality lossless source.
     *
     * For plain playback, force-48k, lossy SUE, DSD, 24/32-bit hi-res, and float
     * paths, the engine must not call this method and no ReplayGain effect is applied.
     * If called on an ineligible session, the native layer returns the default −3.0 dB
     * fallback without scanning metadata.
     *
     * Pass the returned value as `replayGainDb` to [SueBridge.nativeCreate] so the
     * `volume` lavfi stage inside [build_hires_remaster_chain] is driven by the
     * file's own gain metadata instead of the static fallback.
     *
     * @return Peak-derived pre-expansion headroom gain in dB (≤ 0), or −3.0 dB
     *   when no REPLAYGAIN_TRACK_PEAK tag was found.
     * @throws IllegalStateException when called before [open] or after [close].
     */
    fun getReplayGainDb(): Float {
        val h = handle
        check(h != 0L) { "FFmpegDecoder.getReplayGainDb() called before open() or after close()" }
        return nativeGetReplayGainDb(h)
    }

    /**
     * Releases the native session. Safe to call multiple times; subsequent
     * operations on this instance will throw [IllegalStateException].
     */
    fun close() {
        val h = handle
        if (h != 0L) {
            handle = 0L
            nativeClose(h)
        }
    }

    companion object {
        /** Sentinel returned by [readNextBuffer] for a recoverable decode glitch. */
        const val READ_RECOVERABLE_ERROR: Int = -1

        /** Sentinel returned by [readNextBuffer] at end-of-stream. */
        const val READ_EOF: Int = 0

        private const val MICROSECONDS_PER_MILLISECOND = 1_000L
        private const val BITS_PER_KILOBIT = 1_000L

        /**
         * FFmpeg represents DSD sample rates as the bit-clock divided by 8
         * (one byte carries 8 DSD bits). Multiplying by this factor converts
         * an FFmpeg DSD byte-clock rate back to the canonical bit-clock rate
         * expected by [DsdRate.fromSampleRate].
         *
         * Example: DSD64 → FFmpeg reports 352,800 Hz × 8 = 2,822,400 Hz.
         */
        private const val DSD_FFMPEG_BYTE_SHIFT = 8

        init {
            // Step 1: Explicitly load libusb1.0.so from the APK's jniLibs directory
            // before audiophile_native. audiophile_native carries a DT_NEEDED entry for
            // libusb1.0.so; on some Android versions (especially with OEM linker
            // patches) the dynamic linker does not automatically resolve APK-bundled
            // dependencies whose filename contains a dot (e.g. "1.0").
            // Loading it explicitly here guarantees it is in the linker's namespace
            // before the main bridge .so is loaded.
            runCatching {
                System.loadLibrary("usb1.0")   // maps to libusb1.0.so in jniLibs/<ABI>/
            }.onSuccess {
                Log.i(PATH_TAG, "Loaded native library: libusb1.0.so")
            }.onFailure { throwable ->
                // Non-fatal: if audiophile_native already dragged it in via DT_NEEDED
                // this call is a no-op. Only log a warning — we will find out if
                // libusb is actually missing when nativeInitWithFileDescriptor is called.
                Log.w(PATH_TAG, "Could not pre-load libusb1.0.so explicitly — " +
                    "DT_NEEDED resolution may still succeed: ${throwable.message}")
            }

            // Step 2: Load the main native bridge (FFmpeg + USB audio engine).
            runCatching {
                System.loadLibrary("audiophile_native")
            }.onSuccess {
                Log.i(PATH_TAG, "Loaded native library: audiophile_native")
            }.onFailure { throwable ->
                Log.e(PATH_TAG, "Failed to load native library: audiophile_native", throwable)
                throw throwable
            }
        }

        private const val PATH_TAG = "AudiophilePath"

        private fun sanitizeSource(absolutePath: String): String = when {
            absolutePath.startsWith("/proc/self/fd/") -> "/proc/self/fd/<detached>"
            absolutePath.startsWith("content://") -> "content://<redacted>"
            absolutePath.startsWith("file://") -> "file://<redacted>"
            else -> "file:<redacted>"
        }

        @JvmStatic private external fun nativeOpen(path: String, forcePcm: Boolean): Long
        @JvmStatic private external fun nativeClose(handle: Long)
        /**
         * @param dst MUST be a direct [ByteBuffer]; the C layer writes directly into its backing memory.
         *
         * Intentionally NOT marked [FastNative]: this JNI entrypoint can perform
         * FFmpeg demux/decode work and storage reads, so keeping it on regular
         * JNI preserves GC safepoints during long-running background playback.
         */
        @JvmStatic private external fun nativeReadNextBuffer(handle: Long, dst: ByteBuffer, dstCap: Int): Int
        @JvmStatic private external fun nativeReadNextDsdBuffer(handle: Long, dst: ByteArray): Int
        @JvmStatic private external fun nativeSeek(handle: Long, positionUs: Long): Boolean
        @FastNative @JvmStatic private external fun nativeGetSampleRate(handle: Long): Int
        @FastNative @JvmStatic private external fun nativeGetChannelCount(handle: Long): Int
        @FastNative @JvmStatic private external fun nativeGetSourceBitDepth(handle: Long): Int
        @FastNative @JvmStatic private external fun nativeGetAndroidPcmEncoding(handle: Long): Int
        @FastNative @JvmStatic private external fun nativeGetBytesPerSample(handle: Long): Int
        @FastNative @JvmStatic private external fun nativeGetDurationUs(handle: Long): Long
        @FastNative @JvmStatic private external fun nativeGetBitrateBps(handle: Long): Long
        @FastNative @JvmStatic private external fun nativeIsDsd(handle: Long): Boolean
        @FastNative @JvmStatic private external fun nativeIsResampledDsd(handle: Long): Boolean
        @FastNative @JvmStatic private external fun nativeGetDsdRate(handle: Long): Int
        @JvmStatic private external fun nativeGetCodecName(handle: Long): String
        @JvmStatic private external fun nativeGetCodecProfileName(handle: Long): String
        @JvmStatic private external fun nativeGetContainerName(handle: Long): String
        /**
         * Reads [Session::replaygain_db] from the native session.
         * Only populated for ENCODING_PCM_16BIT sessions (CD-quality lossless).
         * Returns −3.0f for all other encoding paths.
         */
        @FastNative @JvmStatic private external fun nativeGetReplayGainDb(handle: Long): Float
    }
}
