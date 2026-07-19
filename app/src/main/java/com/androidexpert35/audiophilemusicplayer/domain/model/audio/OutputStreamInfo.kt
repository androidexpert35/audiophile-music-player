package com.androidexpert35.audiophilemusicplayer.domain.model.audio

/**
 * Describes the complete format of the active audio stream being delivered to
 * the hardware audio sink.
 *
 * The sealed hierarchy maps every transport tier supported by the audiophile
 * pipeline onto a concrete subtype that carries **only** the fields relevant
 * to that tier:
 *
 * - [Pcm] — all standard PCM formats (lossless and lossy)
 * - [Dsd] — native one-bit DSD, DSD-over-PCM (DoP), and the Tier-3 PCM
 *   resampling fallback
 * - [Unknown] — engine idle or no track loaded yet
 *
 * Consumers should `when`-match on the type rather than null-checking scattered
 * nullable top-level fields, eliminating accidental misuse of PCM fields during
 * a DSD session and vice-versa.
 */
sealed interface OutputStreamInfo {

    /**
     * Active codec for this stream. Always available regardless of the concrete
     * subtype so that a simple codec badge can be rendered without a full
     * `when` expression.
     */
    val codec: AudioCodec

    // -------------------------------------------------------------------------
    // Subtypes
    // -------------------------------------------------------------------------

    /**
     * Standard PCM audio output.
     *
     * Covers FLAC, ALAC, WAV, AIFF, MP3, AAC, Opus, Vorbis, and any other
     * format the engine renders as linear PCM at the audio HAL boundary.
     *
     * @property codec Active audio codec.
     * @property sampleRateHz Sample rate delivered to the audio HAL (Hz).
     * @property bitDepth Bit depth of each PCM sample at the HAL boundary.
     * @property bitrateKbps Instantaneous encoded bitrate (kbps); zero for
     *   lossless formats where bitrate is not meaningful.
     */
    data class Pcm(
        override val codec: AudioCodec,
        val sampleRateHz: Int,
        val bitDepth: Int,
        val bitrateKbps: Int,
    ) : OutputStreamInfo

    /**
     * DSD audio output — covers all three transport tiers:
     *
     * - **Tier 1 — Native DSD**: the USB sink accepts one-bit DSD directly;
     *   [outputMode] is [DsdOutputMode.NativeDsd] and [pcmOutput] is `null`.
     * - **Tier 2 — DoP**: DSD bits are packed into a PCM stream for delivery;
     *   [outputMode] is [DsdOutputMode.DoP] and [pcmOutput] carries the PCM
     *   carrier characteristics.
     * - **Tier 3 — PCM resampled**: FFmpeg decimates the DSD stream to hi-res
     *   PCM because the connected output has no DSD capability; [outputMode] is
     *   [DsdOutputMode.Unsupported] and [pcmOutput] carries the PCM sink details.
     *
     * Use [isResampled] and [isDoP] as convenience predicates rather than
     * pattern-matching on [outputMode] directly.
     *
     * @property codec Active DSD codec ([AudioCodec.DSD_64], [AudioCodec.DSD_128],
     *   or [AudioCodec.DSD_256]).
     * @property sourceContainer Human-readable source container label such as
     *   `"DSF"`, `"DSDIFF"`, or `"WavPack DSD"`. `null` when the container
     *   could not be determined by the decoder.
     * @property sourceDsdRate Original one-bit DSD sample-rate family of the
     *   source file.
     * @property outputMode Negotiated DSD transport strategy for the currently
     *   active output device.
     * @property pcmOutput PCM delivery details when DoP or Tier-3 resampled
     *   transport is active; `null` for native one-bit DSD.
     */
    data class Dsd(
        override val codec: AudioCodec,
        val sourceContainer: String?,
        val sourceDsdRate: DsdRate,
        val outputMode: DsdOutputMode,
        val pcmOutput: PcmOutput? = null,
    ) : OutputStreamInfo {

        /**
         * `true` when the DSD source is being decimated to PCM (Tier-3 fallback).
         * The UI should surface the DSD heritage while clearly flagging the
         * PCM transport tier.
         */
        val isResampled: Boolean
            get() = outputMode is DsdOutputMode.Unsupported && pcmOutput != null

        /**
         * `true` when DSD-over-PCM transport is active — DSD bits are packed
         * inside a PCM carrier frame delivered to the audio HAL.
         */
        val isDoP: Boolean
            get() = outputMode is DsdOutputMode.DoP

        /**
         * PCM transport details shared by DoP (Tier 2) and PCM-resampled (Tier 3)
         * sessions. `null` only for native one-bit DSD (Tier 1).
         *
         * @property sampleRateHz PCM carrier sample rate at the audio HAL (Hz).
         *   For DoP this is typically 176 400 or 352 800 Hz depending on the
         *   DSD family; for Tier-3 resampled DSD this is 88 200 Hz (produced by
         *   the `aresample=osr=88200:resampler=soxr:precision=33` lavfi filter).
         * @property bitDepth Bit depth of the PCM carrier. For DoP this is
         *   typically 24; for Tier-3 resampled DSD it is 32-bit float.
         */
        data class PcmOutput(
            val sampleRateHz: Int,
            val bitDepth: Int,
        )
    }

    /**
     * Placeholder used when the playback engine is idle or no track has been
     * loaded yet.
     *
     * All consuming code should treat [Unknown] as "no signal-path information
     * available" and render a suitable empty or disabled state.
     */
    data object Unknown : OutputStreamInfo {
        override val codec: AudioCodec = AudioCodec.UNKNOWN
    }
}

