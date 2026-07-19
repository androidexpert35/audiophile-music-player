package com.androidexpert35.audiophilemusicplayer.data.playback.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verifies that native UAC2 endpoint metadata reaches Kotlin without losing the
 * distinction between carrier width and valid sample resolution.
 */
class UsbPcmEndpointSelectionTest {

    @Test
    fun `given 24 valid bits in four byte subslot when mapped then both depths are retained`() {
        val selection = UsbPcmEndpointSelection.fromNative(
            intArrayOf(
                1,
                2,
                0x01,
                64,
                4,
                24,
            )
        )

        assertEquals(4, selection?.subslotBytes)
        assertEquals(24, selection?.validBitDepth)
    }

    @Test
    fun `given truncated native payload when mapped then selection is rejected`() {
        val selection = UsbPcmEndpointSelection.fromNative(
            intArrayOf(1, 2, 0x01, 64)
        )

        assertNull(selection)
    }

    @Test
    fun `given resolution wider than subslot when mapped then selection is rejected`() {
        val selection = UsbPcmEndpointSelection.fromNative(
            intArrayOf(
                1,
                2,
                0x01,
                64,
                2,
                24,
            )
        )

        assertNull(selection)
    }
}
