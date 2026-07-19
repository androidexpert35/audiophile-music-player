package com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile

import com.androidexpert35.audiophilemusicplayer.data.playback.native_.AudioFormatInfo
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SueProfileResolver].
 */
class SueProfileResolverTest {

    @Test
    fun `given low bitrate mp3 when resolving then aggressive profile is selected`() {
        val resolution = SueProfileResolver.resolve(
            format(codec = AudioCodec.MP3, bitrateKbps = 96, codecName = "mp3")
        )

        assertTrue(resolution.isLossySource)
        assertEquals(SueCodecTier.TIER_LOW, resolution.codecTier)
        assertEquals(SueIntensityProfile.AGGRESSIVE, resolution.intensityProfile)
    }

    @Test
    fun `given high bitrate opus when resolving then bypass profile is selected`() {
        val resolution = SueProfileResolver.resolve(
            format(codec = AudioCodec.OPUS, bitrateKbps = 320, codecName = "opus")
        )

        assertTrue(resolution.isLossySource)
        assertEquals(SueCodecTier.TIER_ULTRA, resolution.codecTier)
        assertEquals(SueIntensityProfile.BYPASS, resolution.intensityProfile)
    }

    @Test
    fun `given flac when resolving then source is treated as lossless`() {
        val resolution = SueProfileResolver.resolve(
            format(codec = AudioCodec.FLAC, bitrateKbps = 900, codecName = "flac")
        )

        assertFalse(resolution.isLossySource)
        assertEquals(SueIntensityProfile.BYPASS, resolution.intensityProfile)
    }

    @Test
    fun `given he aac profile when resolving then high tier flags are applied`() {
        val resolution = SueProfileResolver.resolve(
            format(
                codec = AudioCodec.AAC,
                bitrateKbps = 128,
                codecName = "aac",
                codecProfileName = "HE-AACv2",
            )
        )

        assertEquals(SueCodecTier.TIER_HIGH, resolution.codecTier)
        // SBR already synthesises the high band — excitation on top accentuates
        // the metallic SBR artifact, so the whole TIER_HIGH row is SUBTLE.
        assertEquals(SueIntensityProfile.SUBTLE, resolution.intensityProfile)
        assertTrue(resolution.specialFlags and SueSpecialFlags.SKIP_LAYER2_EQ != 0)
        assertTrue(resolution.specialFlags and SueSpecialFlags.AAC_HE_ODD_HARMONICS_BLEND != 0)
        assertTrue(resolution.specialFlags and SueSpecialFlags.DISABLE_MID_SIDE_WIDENING != 0)
    }

    @Test
    fun `given missing bitrate metadata when resolving lossy source then moderate fallback is selected`() {
        val resolution = SueProfileResolver.resolve(
            format(codec = AudioCodec.VORBIS, bitrateKbps = 0, codecName = "vorbis")
        )

        assertTrue(resolution.isLossySource)
        assertEquals(SueIntensityProfile.MODERATE, resolution.intensityProfile)
    }

    @Test
    fun `given missing bitrate metadata on opus when resolving then subtle fallback is selected`() {
        // Tier-aware fallback: an Opus file with no bitrate metadata is still
        // near-transparent and must not receive MP3-grade MODERATE excitation.
        val resolution = SueProfileResolver.resolve(
            format(codec = AudioCodec.OPUS, bitrateKbps = 0, codecName = "opus")
        )

        assertTrue(resolution.isLossySource)
        assertEquals(SueCodecTier.TIER_ULTRA, resolution.codecTier)
        assertEquals(SueIntensityProfile.SUBTLE, resolution.intensityProfile)
    }

    @Test
    fun `given opus at 96 kbps when resolving then subtle profile is selected`() {
        val resolution = SueProfileResolver.resolve(
            format(codec = AudioCodec.OPUS, bitrateKbps = 96, codecName = "opus")
        )

        assertEquals(SueIntensityProfile.SUBTLE, resolution.intensityProfile)
    }

    @Test
    fun `given opus at 128 kbps when resolving then bypass profile is selected`() {
        // Opus is effectively transparent at 128 kbps in published listening
        // tests; any additive DSP would move it away from the lossless reference.
        val resolution = SueProfileResolver.resolve(
            format(codec = AudioCodec.OPUS, bitrateKbps = 128, codecName = "opus")
        )

        assertEquals(SueIntensityProfile.BYPASS, resolution.intensityProfile)
    }

    @Test
    fun `given wma lossless codec when resolving then source is bypassed`() {
        val resolution = SueProfileResolver.resolve(
            format(codec = AudioCodec.WMA_LOSSLESS, bitrateKbps = 0, codecName = "wmalossless")
        )

        assertFalse(resolution.isLossySource)
        assertEquals(SueIntensityProfile.BYPASS, resolution.intensityProfile)
    }

    @Test
    fun `given mp3 at 192 kbps when resolving then light profile is selected`() {
        // 129-192 kbps column for TIER_LOW was MODERATE before the sibilance fix;
        // it is now LIGHT because MP3 at this bitrate retains high-frequency content
        // well and the MODERATE profile over-excited the 7.5 kHz band.
        val resolution = SueProfileResolver.resolve(
            format(codec = AudioCodec.MP3, bitrateKbps = 192, codecName = "mp3")
        )

        assertTrue(resolution.isLossySource)
        assertEquals(SueCodecTier.TIER_LOW, resolution.codecTier)
        assertEquals(SueIntensityProfile.LIGHT, resolution.intensityProfile)
    }

    @Test
    fun `given mp3 at 256 kbps when resolving then subtle profile is selected`() {
        // 193-256 kbps column for TIER_LOW was LIGHT before the sibilance fix;
        // it is now SUBTLE because the source is near-transparent at this bitrate.
        val resolution = SueProfileResolver.resolve(
            format(codec = AudioCodec.MP3, bitrateKbps = 256, codecName = "mp3")
        )

        assertTrue(resolution.isLossySource)
        assertEquals(SueCodecTier.TIER_LOW, resolution.codecTier)
        assertEquals(SueIntensityProfile.SUBTLE, resolution.intensityProfile)
    }

    @Test
    fun `given mp3 at 320 kbps when resolving then bypass profile is selected`() {
        // ≥257 kbps: LAME keeps ~20.5 kHz of bandwidth at 320 kbps — the source
        // carries the full audible band, so any additive DSP is pure deviation
        // from the lossless reference.
        val resolution = SueProfileResolver.resolve(
            format(codec = AudioCodec.MP3, bitrateKbps = 320, codecName = "mp3")
        )

        assertEquals(SueIntensityProfile.BYPASS, resolution.intensityProfile)
    }

    @Test
    fun `given mid tier aac at 192 kbps when resolving then moderate profile is selected`() {
        // TIER_MID column 2 (129-192 kbps) must remain MODERATE — unchanged.
        val resolution = SueProfileResolver.resolve(
            format(codec = AudioCodec.AAC, bitrateKbps = 192, codecName = "aac")
        )

        assertEquals(SueCodecTier.TIER_MID, resolution.codecTier)
        assertEquals(SueIntensityProfile.MODERATE, resolution.intensityProfile)
    }

    private fun format(
        codec: AudioCodec,
        bitrateKbps: Int,
        codecName: String,
        codecProfileName: String = "",
    ): AudioFormatInfo = AudioFormatInfo(
        sampleRateHz = 44_100,
        channelCount = 2,
        sourceBitDepth = 16,
        androidPcmEncoding = android.media.AudioFormat.ENCODING_PCM_16BIT,
        bytesPerSample = 2,
        durationMs = 180_000,
        bitrateKbps = bitrateKbps,
        codec = codec,
        codecName = codecName,
        codecProfileName = codecProfileName,
    )
}

