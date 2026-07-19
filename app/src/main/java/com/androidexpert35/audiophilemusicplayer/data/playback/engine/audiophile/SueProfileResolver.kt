package com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile

import com.androidexpert35.audiophilemusicplayer.data.playback.native_.AudioFormatInfo
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioCodec

/**
 * Resolves SUE activation and intensity from decoded-track metadata.
 *
 * The resolver is pure Kotlin so its gating and matrix logic can be unit tested
 * without Android framework or JNI dependencies.
 */
object SueProfileResolver {

    /**
     * Resolves the complete SUE decision for [format].
     *
     * @param format Decoded track metadata captured at FFmpeg open time.
     * @return Immutable per-track SUE resolution.
     */
    internal fun resolve(format: AudioFormatInfo): SueProfileResolution {
        val rawCodecName = format.codecName.trim()
        val rawProfileName = format.codecProfileName.trim()
        val isLossySource = isLossySource(
            codec = format.codec,
            codecName = rawCodecName,
            codecProfileName = rawProfileName,
            isDsd = format.isDsd,
            isResampledDsd = format.isResampledDsd,
        )
        val codecTier = resolveCodecTier(
            codec = format.codec,
            codecName = rawCodecName,
            codecProfileName = rawProfileName,
            isLossySource = isLossySource,
        )
        val intensityProfile = resolveIntensityProfile(
            codecTier = codecTier,
            bitrateKbps = format.bitrateKbps,
            isLossySource = isLossySource,
        )
        val specialFlags = resolveSpecialFlags(rawProfileName)

        return SueProfileResolution(
            isLossySource = isLossySource,
            codecTier = codecTier,
            intensityProfile = intensityProfile,
            specialFlags = specialFlags,
            codecDisplayName = buildCodecDisplayName(format.codec, rawCodecName, rawProfileName),
        )
    }

    // Matrix mirror of PROFILE_MATRIX in sue_bridge.cpp — keep the two in sync.
    // Calibrated against measured encoder behaviour: LAME MP3 low-pass defaults
    // (~16.4 kHz @96 kbps … ~20.5 kHz @320 kbps) mean ≥257 kbps sources keep the
    // full audible band, so additive DSP is pure deviation from the lossless
    // reference → BYPASS. AAC-HE already synthesises the high band via SBR, and
    // Opus is fullband from ~64-96 kbps and near-transparent at 128 kbps.
    private fun resolveIntensityProfile(
        codecTier: SueCodecTier,
        bitrateKbps: Int,
        isLossySource: Boolean,
    ): SueIntensityProfile {
        if (!isLossySource) return SueIntensityProfile.BYPASS
        if (bitrateKbps <= 0) {
            // Tier-aware fallback: MODERATE excitation is only safe for the
            // inefficient codecs; unknown-bitrate Opus / AAC-HE stays SUBTLE.
            return when (codecTier) {
                SueCodecTier.TIER_LOW,
                SueCodecTier.TIER_MID -> SueIntensityProfile.MODERATE
                SueCodecTier.TIER_HIGH,
                SueCodecTier.TIER_ULTRA -> SueIntensityProfile.SUBTLE
            }
        }

        val column = when {
            bitrateKbps <= 96 -> 0
            bitrateKbps <= 128 -> 1
            bitrateKbps <= 192 -> 2
            bitrateKbps <= 256 -> 3
            else -> 4
        }

        return when (codecTier) {
            SueCodecTier.TIER_LOW -> arrayOf(
                SueIntensityProfile.AGGRESSIVE,
                SueIntensityProfile.AGGRESSIVE,
                SueIntensityProfile.LIGHT,
                SueIntensityProfile.SUBTLE,
                SueIntensityProfile.BYPASS,   // ≥257 kbps: full-band source, no correction defensible
            )[column]
            SueCodecTier.TIER_MID -> arrayOf(
                SueIntensityProfile.AGGRESSIVE,
                SueIntensityProfile.MODERATE,
                SueIntensityProfile.MODERATE,
                SueIntensityProfile.SUBTLE,   // was LIGHT — 193-256 kbps AAC/Vorbis is near-transparent
                SueIntensityProfile.BYPASS,
            )[column]
            SueCodecTier.TIER_HIGH -> arrayOf(
                SueIntensityProfile.SUBTLE,   // SBR already rebuilt the high band; excitation on top
                SueIntensityProfile.SUBTLE,   // accentuates the metallic SBR artifact
                SueIntensityProfile.SUBTLE,
                SueIntensityProfile.BYPASS,
                SueIntensityProfile.BYPASS,
            )[column]
            SueCodecTier.TIER_ULTRA -> arrayOf(
                SueIntensityProfile.SUBTLE,   // Opus is fullband from ~64-96 kbps; artifacts are
                SueIntensityProfile.BYPASS,   // temporal/tonal, not bandwidth loss — excitation
                SueIntensityProfile.BYPASS,   // cannot repair them
                SueIntensityProfile.BYPASS,
                SueIntensityProfile.BYPASS,
            )[column]
        }
    }

    private fun resolveCodecTier(
        codec: AudioCodec,
        codecName: String,
        codecProfileName: String,
        isLossySource: Boolean,
    ): SueCodecTier {
        if (!isLossySource) return SueCodecTier.TIER_MID
        if (isAacHeProfile(codecProfileName)) return SueCodecTier.TIER_HIGH

        val normalizedCodecName = codecName.lowercase()
        return when {
            codec == AudioCodec.MP3 || codec == AudioCodec.WMA -> SueCodecTier.TIER_LOW
            codec == AudioCodec.AAC || codec == AudioCodec.VORBIS -> SueCodecTier.TIER_MID
            codec == AudioCodec.OPUS -> SueCodecTier.TIER_ULTRA
            normalizedCodecName.contains("mp3") || normalizedCodecName.contains("wma") || normalizedCodecName.contains("wmav") ->
                SueCodecTier.TIER_LOW
            normalizedCodecName.contains("aac") || normalizedCodecName.contains("vorbis") || normalizedCodecName.contains("ogg") ->
                SueCodecTier.TIER_MID
            normalizedCodecName.contains("opus") -> SueCodecTier.TIER_ULTRA
            else -> SueCodecTier.TIER_MID
        }
    }

    private fun resolveSpecialFlags(codecProfileName: String): Int {
        val normalizedProfileName = codecProfileName.lowercase()
        if (!isAacHeProfile(codecProfileName)) return 0

        var flags = SueSpecialFlags.SKIP_LAYER2_EQ or SueSpecialFlags.AAC_HE_ODD_HARMONICS_BLEND
        if (normalizedProfileName.contains("v2") || normalizedProfileName.contains("ps")) {
            flags = flags or SueSpecialFlags.DISABLE_MID_SIDE_WIDENING
        }
        return flags
    }

    private fun isLossySource(
        codec: AudioCodec,
        codecName: String,
        codecProfileName: String,
        isDsd: Boolean,
        isResampledDsd: Boolean,
    ): Boolean {
        if (isDsd || isResampledDsd) return false
        if (codec.isLossless) return false

        val normalizedCodecName = codecName.lowercase()
        val normalizedProfileName = codecProfileName.lowercase()
        if (normalizedCodecName.isBlank() && codec == AudioCodec.UNKNOWN) return false

        val losslessMarkers = listOf(
            "lossless", "flac", "alac", "pcm", "wav", "wave", "aiff", "dsd", "ape", "tta", "wavpack"
        )
        if (losslessMarkers.any(normalizedCodecName::contains) || losslessMarkers.any(normalizedProfileName::contains)) {
            return false
        }

        return when (codec) {
            AudioCodec.AAC,
            AudioCodec.MP3,
            AudioCodec.OPUS,
            AudioCodec.VORBIS,
            AudioCodec.WMA -> true
            AudioCodec.UNKNOWN -> {
                listOf("aac", "mp3", "opus", "vorbis", "ogg", "wma", "wmav", "m4a")
                    .any(normalizedCodecName::contains)
            }
            else -> false
        }
    }

    private fun isAacHeProfile(codecProfileName: String): Boolean {
        val normalized = codecProfileName.lowercase()
        return normalized.contains("he-aac") ||
            normalized.contains("aac he") ||
            normalized.contains("sbr")
    }

    private fun buildCodecDisplayName(
        codec: AudioCodec,
        codecName: String,
        codecProfileName: String,
    ): String {
        return when {
            isAacHeProfile(codecProfileName) && codecProfileName.contains("v2", ignoreCase = true) -> "AAC-HE v2"
            isAacHeProfile(codecProfileName) -> "AAC-HE"
            codec != AudioCodec.UNKNOWN -> codec.displayName
            codecName.isNotBlank() -> codecName.uppercase()
            else -> AudioCodec.UNKNOWN.displayName
        }
    }
}


