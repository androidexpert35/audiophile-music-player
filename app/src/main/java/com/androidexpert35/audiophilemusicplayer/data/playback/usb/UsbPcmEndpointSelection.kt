package com.androidexpert35.audiophilemusicplayer.data.playback.usb

/**
 * Describes the UAC2 PCM alternate setting selected by the native descriptor parser.
 *
 * [subslotBytes] is the physical USB carrier width. [validBitDepth] is the selected
 * endpoint's `bBitResolution`, which may be narrower than that carrier (for example,
 * 24 valid bits in a four-byte subslot).
 *
 * @property interfaceNumber Streaming interface containing the selected alternate setting.
 * @property altSetting Non-zero alternate setting that activates the PCM endpoint.
 * @property endpointAddress Isochronous OUT endpoint address.
 * @property effectiveBytesPerUframe Maximum payload exposed by the endpoint per microframe.
 * @property subslotBytes Bytes occupied by one channel sample on the USB wire.
 * @property validBitDepth Sample bits consumed by the DAC within each subslot.
 */
internal data class UsbPcmEndpointSelection(
    val interfaceNumber: Int,
    val altSetting: Int,
    val endpointAddress: Int,
    val effectiveBytesPerUframe: Int,
    val subslotBytes: Int,
    val validBitDepth: Int,
) {

    companion object {

        private const val NATIVE_FIELD_COUNT = 6

        /**
         * Validates and maps the compact array returned by the JNI descriptor scanner.
         *
         * @param values Native fields in interface, alternate-setting, endpoint,
         *   bandwidth, subslot, and valid-resolution order.
         * @return A validated selection, or `null` when the JNI payload is malformed.
         */
        fun fromNative(values: IntArray?): UsbPcmEndpointSelection? {
            if (values == null || values.size < NATIVE_FIELD_COUNT) return null

            val selection = UsbPcmEndpointSelection(
                interfaceNumber = values[0],
                altSetting = values[1],
                endpointAddress = values[2],
                effectiveBytesPerUframe = values[3],
                subslotBytes = values[4],
                validBitDepth = values[5],
            )
            return selection.takeIf {
                it.interfaceNumber >= 0 &&
                    it.altSetting > 0 &&
                    it.endpointAddress in 1..0xFF &&
                    it.effectiveBytesPerUframe > 0 &&
                    it.subslotBytes in 1..Int.SIZE_BYTES &&
                    it.validBitDepth in 1..(it.subslotBytes * Byte.SIZE_BITS)
            }
        }
    }
}
