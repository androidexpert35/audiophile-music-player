package com.androidexpert35.audiophilemusicplayer.domain.model.audio

/**
 * Negotiated transport strategy for DSD playback on the currently active output.
 *
 * The engine prefers native one-bit DSD output whenever the selected sink can
 * prove support for the requested rate. When native DSD is unavailable but the
 * sink can carry a sufficiently high PCM stream, playback falls back to DoP.
 */
sealed interface DsdOutputMode {
    /**
     * Native one-bit DSD transport is available.
     *
     * @property maxRate Highest DSD family the active output can carry natively.
     */
    data class NativeDsd(val maxRate: DsdRate) : DsdOutputMode

    /**
     * DSD-over-PCM transport is available.
     *
     * @property maxPcmRate Highest PCM carrier rate in Hertz the active output can sustain for DoP.
     */
    data class DoP(val maxPcmRate: Int) : DsdOutputMode

    /** No supported DSD transport is currently available. */
    data object Unsupported : DsdOutputMode
}

