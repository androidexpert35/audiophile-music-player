package com.androidexpert35.audiophilemusicplayer.data.playback.usb

import android.util.Log
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.DsdRate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Best-effort parser for USB Audio Class format descriptors.
 *
 * The parser currently recognises Type I class-specific interface descriptors
 * and extracts discrete or range-based sample-rate declarations. When the DAC
 * exposes vendor-specific or malformed descriptors, callers should fall back to
 * a curated negotiation ladder rather than failing hard.
 */
@Singleton
class UsbAudioDescriptorParser @Inject constructor() {

    /**
     * Parses supported PCM output profiles from the raw USB configuration
     * descriptor blob returned by [android.hardware.usb.UsbDeviceConnection.getRawDescriptors].
     *
     * @param rawDescriptors Raw descriptor bytes read from the permitted USB
     *   device connection.
     * @return Distinct output profiles ordered by descending fidelity.
     */
    fun parseTypeIFormats(rawDescriptors: ByteArray): List<UsbAudioOutputProfile> {
        if (rawDescriptors.isEmpty()) return emptyList()

        val parsedProfiles = linkedSetOf<UsbAudioOutputProfile>()
        var cursor = 0
        while (cursor < rawDescriptors.size) {
            val length = rawDescriptors[cursor].toUnsignedInt()
            if (length <= 0) {
                cursor += 1
                continue
            }
            if (cursor + length > rawDescriptors.size) {
                Log.w(TAG, "Descriptor overrun at offset=$cursor length=$length")
                break
            }

            val descriptorType = rawDescriptors.getOrNull(cursor + 1)?.toUnsignedInt() ?: 0
            val descriptorSubType = rawDescriptors.getOrNull(cursor + 2)?.toUnsignedInt() ?: 0
            if (descriptorType == USB_DT_CS_INTERFACE && descriptorSubType == UAC_FORMAT_TYPE) {
                parseFormatTypeDescriptor(rawDescriptors, cursor, length)
                    .forEach(parsedProfiles::add)
            }
            cursor += max(length, 1)
        }

        return parsedProfiles
            .sortedWith(
                compareByDescending<UsbAudioOutputProfile> { it.sampleRateHz }
                    .thenByDescending { it.bitDepth }
                    .thenByDescending { it.channelCount }
            )
    }

    /**
     * Parses native DSD-capable rate declarations from the raw USB descriptor blob.
     *
     * This is intentionally conservative: Android USB DACs expose DSD support
     * through several vendor-specific descriptor shapes, so the parser accepts
     * only descriptors whose sample rates match known DSD families and whose
     * format fields indicate one-bit or Type III-style transport.
     *
     * @param rawDescriptors Raw descriptor bytes read from the permitted USB device.
     * @return Distinct native DSD families ordered by descending rate.
     */
    fun parseDsdRates(rawDescriptors: ByteArray): List<DsdRate> {
        if (rawDescriptors.isEmpty()) return emptyList()

        val parsedRates = linkedSetOf<DsdRate>()
        var cursor = 0
        while (cursor < rawDescriptors.size) {
            val length = rawDescriptors[cursor].toUnsignedInt()
            if (length <= 0) {
                cursor += 1
                continue
            }
            if (cursor + length > rawDescriptors.size) {
                Log.w(TAG, "Descriptor overrun at offset=$cursor length=$length while parsing DSD")
                break
            }

            val descriptorType = rawDescriptors.getOrNull(cursor + 1)?.toUnsignedInt() ?: 0
            val descriptorSubType = rawDescriptors.getOrNull(cursor + 2)?.toUnsignedInt() ?: 0
            if (descriptorType == USB_DT_CS_INTERFACE && descriptorSubType == UAC_FORMAT_TYPE) {
                parseDsdFormatDescriptor(rawDescriptors, cursor, length)
                    .forEach(parsedRates::add)
            }
            cursor += max(length, 1)
        }

        return parsedRates.sortedByDescending(DsdRate::multiplier)
    }

    private fun parseFormatTypeDescriptor(
        rawDescriptors: ByteArray,
        offset: Int,
        length: Int,
    ): List<UsbAudioOutputProfile> {
        val formatType = rawDescriptors.getOrNull(offset + 3)?.toUnsignedInt() ?: return emptyList()
        if (formatType != FORMAT_TYPE_I) {
            Log.d(TAG, "Ignoring non-Type-I USB audio format descriptor type=$formatType")
            return emptyList()
        }

        val channelCount = rawDescriptors.getOrNull(offset + 4)?.toUnsignedInt()?.coerceAtLeast(1) ?: 2
        val subframeSize = rawDescriptors.getOrNull(offset + 5)?.toUnsignedInt()?.coerceAtLeast(1) ?: 2
        val bitDepth = rawDescriptors.getOrNull(offset + 6)?.toUnsignedInt()?.coerceAtLeast(subframeSize * 8)
            ?: (subframeSize * 8)
        val sampleRateCount = rawDescriptors.getOrNull(offset + 7)?.toUnsignedInt() ?: return emptyList()

        if (sampleRateCount == 0) {
            val minRate = read24Le(rawDescriptors, offset + 8)
            val maxRate = read24Le(rawDescriptors, offset + 11)
            if (minRate <= 0 || maxRate <= 0 || maxRate < minRate) {
                Log.w(TAG, "Invalid continuous sample-rate range: min=$minRate max=$maxRate")
                return emptyList()
            }
            return fallbackNegotiationProfiles()
                .filter { it.sampleRateHz in minRate..maxRate }
                .map { it.copy(channelCount = channelCount, bitDepth = bitDepth) }
        }

        val profiles = mutableListOf<UsbAudioOutputProfile>()
        val firstRateOffset = offset + 8
        repeat(sampleRateCount) { index ->
            val sampleRateHz = read24Le(rawDescriptors, firstRateOffset + (index * 3))
            if (sampleRateHz > 0 && firstRateOffset + (index * 3) + 2 < offset + length) {
                profiles += UsbAudioOutputProfile(
                    sampleRateHz = sampleRateHz,
                    bitDepth = bitDepth,
                    channelCount = channelCount,
                )
            }
        }
        return profiles
    }

    private fun parseDsdFormatDescriptor(
        rawDescriptors: ByteArray,
        offset: Int,
        length: Int,
    ): List<DsdRate> {
        val formatType = rawDescriptors.getOrNull(offset + 3)?.toUnsignedInt() ?: return emptyList()
        val subframeSize = rawDescriptors.getOrNull(offset + 5)?.toUnsignedInt()?.coerceAtLeast(1) ?: 1
        val bitResolution = rawDescriptors.getOrNull(offset + 6)?.toUnsignedInt()?.coerceAtLeast(1) ?: 1
        val sampleRateCount = rawDescriptors.getOrNull(offset + 7)?.toUnsignedInt() ?: return emptyList()

        val looksLikeDsdDescriptor =
            formatType == FORMAT_TYPE_III || bitResolution == 1 || subframeSize == 1
        if (!looksLikeDsdDescriptor) {
            return emptyList()
        }

        val discreteRates = if (sampleRateCount == 0) {
            val minRate = read24Le(rawDescriptors, offset + 8)
            val maxRate = read24Le(rawDescriptors, offset + 11)
            supportedDsdRates.filter { it.sampleRateHz in minRate..maxRate }
        } else {
            val firstRateOffset = offset + 8
            buildList {
                repeat(sampleRateCount) { index ->
                    val sampleRateHz = read24Le(rawDescriptors, firstRateOffset + (index * 3))
                    DsdRate.fromSampleRate(sampleRateHz)?.let(::add)
                }
            }
        }

        return discreteRates.distinct()
    }

    /**
     * Returns the fallback USB negotiation ladder used when descriptor parsing
     * fails or the device exposes a non-standard format block.
     */
    fun fallbackNegotiationProfiles(): List<UsbAudioOutputProfile> = commonUsbPcmSampleRatesHz
        .flatMap { sampleRateHz ->
            commonUsbPcmBitDepths.map { bitDepth ->
                UsbAudioOutputProfile(
                    sampleRateHz = sampleRateHz,
                    bitDepth = bitDepth,
                    channelCount = DEFAULT_FALLBACK_CHANNEL_COUNT,
                )
            }
        }
        .sortedWith(
            compareByDescending<UsbAudioOutputProfile> { it.sampleRateHz }
                .thenByDescending { it.bitDepth }
                .thenByDescending { it.channelCount }
        )

    private fun read24Le(rawDescriptors: ByteArray, offset: Int): Int {
        val b0 = rawDescriptors.getOrNull(offset)?.toUnsignedInt() ?: return -1
        val b1 = rawDescriptors.getOrNull(offset + 1)?.toUnsignedInt() ?: return -1
        val b2 = rawDescriptors.getOrNull(offset + 2)?.toUnsignedInt() ?: return -1
        return b0 or (b1 shl 8) or (b2 shl 16)
    }

    private fun Byte.toUnsignedInt(): Int = toInt() and 0xFF

    private companion object {
        const val TAG = "UsbAudioDescParser"
        const val USB_DT_CS_INTERFACE = 0x24
        const val UAC_FORMAT_TYPE = 0x02
        const val FORMAT_TYPE_I = 0x01
        const val FORMAT_TYPE_III = 0x03
        const val DEFAULT_FALLBACK_CHANNEL_COUNT = 2

        val supportedDsdRates: List<DsdRate> = listOf(
            DsdRate.DSD64,
            DsdRate.DSD128,
            DsdRate.DSD256,
        )

        val commonUsbPcmSampleRatesHz: List<Int> = listOf(
            8_000,
            11_025,
            16_000,
            22_050,
            32_000,
            44_100,
            48_000,
            88_200,
            96_000,
            176_400,
            192_000,
            352_800,
            384_000,
        )

        val commonUsbPcmBitDepths: List<Int> = listOf(16, 24, 32)
    }
}

