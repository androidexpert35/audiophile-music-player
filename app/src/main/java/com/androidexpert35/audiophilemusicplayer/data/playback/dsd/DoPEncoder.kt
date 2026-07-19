package com.androidexpert35.audiophilemusicplayer.data.playback.dsd

import android.media.AudioFormat
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.DsdRate

/**
 * Stateful DSD-over-PCM encoder.
 *
 * Consumes MSB-first interleaved DSD bytes and emits 24-bit packed
 * little-endian PCM carrier frames that conform to the DoP v1.1 specification.
 * The DoP v1.1 word is a 24-bit PCM sample structured as
 * `[marker : DSD_MSB : DSD_LSB]` with `marker` in the most-significant byte.
 *
 * Using a 24-bit carrier matches the DoP specification exactly: a 32-bit
 * container would place the 0x05 / 0xFA marker byte into the 32-bit sign
 * position, which the Android audio HAL interprets as extreme positive /
 * negative PCM excursions whenever the HAL does not preserve the payload
 * bit-for-bit. That caused the "white noise with quiet music" symptom we saw
 * with the previous 32-bit encoder. The 24-bit packed carrier avoids the
 * sign-extension hazard and is the standard transport every DoP-capable DAC
 * expects.
 *
 * The DoP marker must alternate continuously across buffer boundaries, so each
 * [DoPEncoder] instance owns its own marker state and must remain
 * thread-confined.
 *
 * @property effectiveDsdRate Effective DSD family being transported after any
 *   explicit decimation step (for example DSD256 → DSD128).
 */
class DoPEncoder(
    private val effectiveDsdRate: DsdRate,
) {
    // The DoP marker is defined over the whole stream, not per input buffer.
    // Keeping it as mutable encoder state preserves the required 0x05 / 0xFA
    // alternation even when the engine feeds the encoder in arbitrary chunks.
    private var usePrimaryMarker: Boolean = true

    /** PCM carrier sample rate in Hertz derived from [effectiveDsdRate]. */
    val outputSampleRate: Int = effectiveDsdRate.sampleRateHz / DOP_RATE_DIVISOR

    /** Android PCM carrier encoding used by the DoP output stream. */
    val outputEncoding: Int = AudioFormat.ENCODING_PCM_24BIT_PACKED

    /**
     * Encodes [frameCount] stereo DoP frames from [dsdBuffer].
     *
     * Each output frame consumes two interleaved stereo DSD byte-pairs
     * (`L0 R0 L1 R1`) so that each 24-bit PCM channel sample carries 16 bits
     * of one DSD channel plus the alternating DoP marker byte. Samples are
     * written as 3 bytes in little-endian order:
     * `[DSD_LSB][DSD_MSB][marker]`.
     *
     * @param dsdBuffer Raw MSB-first interleaved DSD bytes.
     * @param frameCount Number of stereo DoP frames to encode from [dsdBuffer].
     * @return Little-endian 24-bit packed PCM DoP carrier bytes.
     */
    fun encode(dsdBuffer: ByteArray, frameCount: Int): ByteArray {
        val safeFrameCount = frameCount.coerceAtMost(dsdBuffer.size / DSD_BYTES_PER_STEREO_DOP_FRAME)
        if (safeFrameCount <= 0) return ByteArray(0)

        val output = ByteArray(safeFrameCount * PCM_BYTES_PER_STEREO_DOP_FRAME)
        var inputOffset = 0
        var outputOffset = 0

        repeat(safeFrameCount) {
            val marker = if (usePrimaryMarker) PRIMARY_MARKER else SECONDARY_MARKER
            usePrimaryMarker = !usePrimaryMarker

            writeCarrierSample(
                target = output,
                offset = outputOffset,
                marker = marker,
                dsdMsb = dsdBuffer[inputOffset].toInt() and BYTE_MASK,
                dsdLsb = dsdBuffer[inputOffset + 2].toInt() and BYTE_MASK,
            )
            writeCarrierSample(
                target = output,
                offset = outputOffset + PCM_BYTES_PER_SAMPLE,
                marker = marker,
                dsdMsb = dsdBuffer[inputOffset + 1].toInt() and BYTE_MASK,
                dsdLsb = dsdBuffer[inputOffset + 3].toInt() and BYTE_MASK,
            )
            inputOffset += DSD_BYTES_PER_STEREO_DOP_FRAME
            outputOffset += PCM_BYTES_PER_STEREO_DOP_FRAME
        }

        return output
    }

    // Writes one 24-bit DoP sample in little-endian order. Layout in memory:
    //   byte0 = DSD_LSB, byte1 = DSD_MSB, byte2 = marker (0x05 / 0xFA).
    // This matches the 24-bit word value `(marker << 16) | (dsdMsb << 8) | dsdLsb`
    // as consumed by every DoP v1.1-compliant DAC.
    private fun writeCarrierSample(
        target: ByteArray,
        offset: Int,
        marker: Int,
        dsdMsb: Int,
        dsdLsb: Int,
    ) {
        target[offset] = (dsdLsb and BYTE_MASK).toByte()
        target[offset + 1] = (dsdMsb and BYTE_MASK).toByte()
        target[offset + 2] = (marker and BYTE_MASK).toByte()
    }

    private companion object {
        const val DOP_RATE_DIVISOR = 16
        const val PRIMARY_MARKER = 0x05
        const val SECONDARY_MARKER = 0xFA
        const val BYTE_MASK = 0xFF
        const val PCM_BYTES_PER_SAMPLE = 3
        const val DSD_BYTES_PER_STEREO_DOP_FRAME = 4
        const val PCM_BYTES_PER_STEREO_DOP_FRAME = 6
    }
}

