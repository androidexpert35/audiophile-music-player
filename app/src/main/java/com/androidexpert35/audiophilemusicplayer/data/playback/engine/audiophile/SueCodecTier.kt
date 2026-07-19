package com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile

import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioCodec

/**
 * Efficiency tier assigned to a lossy codec before the SUE intensity profile
 * is resolved on the codec tier × bitrate matrix.
 *
 * Higher tiers indicate more efficient psychoacoustic models — less artifact
 * damage at equivalent bitrates — and therefore require less aggressive
 * enhancement. The classification mirrors the table in the SUE design doc.
 *
 * @property value Integer passed to the native layer via [SueBridge.nativeCreate].
 */
enum class SueCodecTier(val value: Int) {
    /**
     * Oldest psychoacoustic model; highest artifact rate at any bitrate.
     * Applies to: MP3 (all variants), WMA lossy.
     */
    TIER_LOW(0),

    /**
     * Significantly more efficient than MP3; artifacts start below ~96 kbps.
     * Applies to: AAC-LC, OGG Vorbis (and any unrecognised lossy codec as a
     * conservative fallback).
     */
    TIER_MID(1),

    /**
     * Already incorporates Spectral Band Replication; enhancement must be
     * restrained. Layer 2 (Spectral EQ) is skipped for SBR-capable codecs.
     * Applies to: AAC-HE v1 / v2.
     *
     * Note: [AudioCodec.AAC] combines LC and HE variants in one enum entry.
     * HE flavours require a lower-level codec introspection (e.g. reading
     * `AVCodecContext.profile`); until that probe is implemented HE files fall
     * back to [TIER_MID] which is the safe conservative choice.
     */
    TIER_HIGH(2),

    /**
     * Near-transparent at ≥ 96 kbps; enhancement only needed at very low bitrates.
     * Applies to: Opus.
     */
    TIER_ULTRA(3);

    companion object {

        /**
         * Classifies [codec] into a [SueCodecTier] for the intensity matrix.
         *
         * @param codec The [AudioCodec] resolved from the decoded stream.
         * @return The appropriate [SueCodecTier]; defaults to [TIER_MID] for
         *   unknown or unrecognised lossy codecs.
         */
        fun from(codec: AudioCodec): SueCodecTier = when (codec) {
            AudioCodec.MP3                    -> TIER_LOW
            // AAC maps to TIER_MID (AAC-LC; HE detection requires codec profile probe)
            AudioCodec.AAC, AudioCodec.VORBIS -> TIER_MID
            AudioCodec.OPUS                   -> TIER_ULTRA
            else                              -> TIER_MID  // WMA, UNKNOWN → safe fallback
        }
    }
}

