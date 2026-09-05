package com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile

import com.androidexpert35.audiophilemusicplayer.data.playback.native_.AudioFormatInfo
import com.androidexpert35.audiophilemusicplayer.data.playback.native_.FFmpegDecoder

/**
 * Manages the preloaded next-track state used by [BitPerfectPlaybackEngine]
 * for gapless and non-gapless auto-advance transitions.
 *
 * When the decoded formats of the current and next tracks are compatible
 * (same encoding, sample rate, and channel count), the queue holds a fully
 * opened [FFmpegDecoder] alongside the format and URI, so the engine can swap
 * decoders at end-of-stream **without** touching the output sink.
 *
 * When formats are incompatible, or the sink owns an independent native
 * decoder pump, only the URI is stored. The engine performs a full
 * sink-and-decoder reload on end-of-stream, producing a brief but correct
 * audible transition.
 *
 * A preloaded entry also owns the [BitPerfectSourceHandle] its decoder was
 * opened from. The handle is released by [clear] when the preload is discarded,
 * and handed to the engine by [consumeCompatibleEntry] when the swap happens —
 * so the incoming track's descriptor survives becoming the current track.
 */
internal class BitPerfectGaplessQueue {

    /**
     * Preloaded decoder for the next queue item.
     *
     * Non-null only when the next track's format is compatible with the current
     * one and a gapless swap can be performed.
     */
    var nextDecoder: FFmpegDecoder? = null
        private set

    /**
     * Decoded format for [nextDecoder].
     *
     * Always set together with [nextDecoder]; `null` when only a URI is queued.
     */
    var nextFormat: AudioFormatInfo? = null
        private set

    /**
     * URI of the enqueued next track.
     *
     * Set for both gapless and non-gapless enqueue paths.
     */
    var nextUri: String? = null
        private set

    /**
     * Open source handle backing [nextDecoder].
     *
     * Always set together with [nextDecoder]; ownership moves to the engine on
     * [consumeCompatibleEntry] and is released here by [clear].
     */
    private var nextSourceHandle: BitPerfectSourceHandle? = null

    /**
     * `true` when a fully preloaded, format-compatible decoder is ready for
     * a gapless sink-preserving swap at the next end-of-stream.
     */
    val hasCompatiblePreload: Boolean
        get() = nextDecoder != null && nextFormat != null && nextUri != null

    /**
     * `true` when a URI-only entry (format unknown / incompatible) has been
     * enqueued for a non-gapless transition at the next end-of-stream.
     *
     * Used by [BitPerfectDiagnosticsLogger.onTrackEof] to record whether the
     * next track was at least enqueued before EOF fired.
     */
    val hasPendingUri: Boolean
        get() = nextUri != null && nextDecoder == null

    /**
     * Stores a format-compatible preloaded [decoder] for a future gapless swap.
     *
     * @param decoder Opened [FFmpegDecoder] ready to read the next track.
     * @param format Decoded audio format for [decoder].
     * @param uri Content URI of the next track.
     * @param sourceHandle Open source [decoder] was built from; this queue takes
     *   ownership of it until the entry is consumed or cleared.
     */
    fun enqueueCompatible(
        decoder: FFmpegDecoder,
        format: AudioFormatInfo,
        uri: String,
        sourceHandle: BitPerfectSourceHandle,
    ) {
        nextDecoder = decoder
        nextFormat = format
        nextUri = uri
        nextSourceHandle = sourceHandle
    }

    /**
     * Records only the [uri] when the next track's format is incompatible with
     * the current one, preventing a premature full preload.
     *
     * @param uri Content URI of the next track.
     */
    fun enqueueUriOnly(uri: String) {
        nextUri = uri
    }

    /**
     * Closes [nextDecoder], releases the preloaded source descriptor, and clears
     * all queued state.
     *
     * Safe to call when nothing is queued — closing a `null` decoder is a no-op.
     */
    fun clear() {
        runCatching { nextDecoder?.close() }
        runCatching { nextSourceHandle?.close() }
        nextDecoder = null
        nextFormat = null
        nextUri = null
        nextSourceHandle = null
    }

    /**
     * Consumes and returns the fully preloaded gapless entry, clearing internal state.
     *
     * Should be called when the current track reaches end-of-stream and the
     * engine is ready to perform a sink-preserving decoder swap.
     *
     * @return The preloaded [GaplessEntry], or `null` when no compatible preload
     *   is available.
     */
    fun consumeCompatibleEntry(): GaplessEntry? {
        val decoder = nextDecoder ?: return null
        val format = nextFormat ?: return null
        val uri = nextUri ?: return null
        val sourceHandle = nextSourceHandle ?: return null
        nextDecoder = null
        nextFormat = null
        nextUri = null
        nextSourceHandle = null
        return GaplessEntry(decoder, format, uri, sourceHandle)
    }

    /**
     * Consumes and returns only the queued [nextUri], clearing it from state.
     *
     * Used for the non-gapless (format-mismatch) advance path where only a
     * URI was stored.
     *
     * @return The queued URI, or `null` if none was stored.
     */
    fun consumePendingUri(): String? {
        val uri = nextUri ?: return null
        nextUri = null
        return uri
    }

    companion object {

        /**
         * Returns `true` when the active pipeline can swap to [next] by replacing
         * only the decoder owned by [BitPerfectPlaybackEngine].
         *
         * Besides matching PCM shape, the sink must consume that engine-owned
         * decoder. A native decoder-pump sink owns a separate decoder handle;
         * swapping the Kotlin-side decoder would leave the native pump at EOF and
         * immediately terminate the incoming track.
         *
         * DSD tracks are always excluded from gapless preload because each DSD
         * track requires its own DoP / native transport negotiation during load.
         *
         * @param current Format of the track currently being decoded.
         * @param next Format of the candidate next track.
         * @param sinkUsesNativeDecoderPump Whether the sink reads from its own
         *   native decoder instead of the engine-owned [FFmpegDecoder].
         */
        fun canSwapDecoderInPlace(
            current: AudioFormatInfo,
            next: AudioFormatInfo,
            sinkUsesNativeDecoderPump: Boolean,
        ): Boolean =
            !sinkUsesNativeDecoderPump &&
                !current.isDsd && !next.isDsd &&
                next.sampleRateHz == current.sampleRateHz &&
                next.androidPcmEncoding == current.androidPcmEncoding &&
                next.channelCount == current.channelCount
    }
}

/**
 * Immutable snapshot of a fully preloaded gapless next-track entry.
 *
 * @property decoder Opened [FFmpegDecoder] ready to read the next track.
 * @property format Decoded audio format for [decoder].
 * @property uri Content URI of the preloaded track.
 * @property sourceHandle Open source the decoder was built from. Ownership
 *   passes to the consumer, which must close it when the track it belongs to
 *   is replaced.
 */
internal data class GaplessEntry(
    val decoder: FFmpegDecoder,
    val format: AudioFormatInfo,
    val uri: String,
    val sourceHandle: BitPerfectSourceHandle,
)

