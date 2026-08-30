package com.androidexpert35.audiophilemusicplayer.data.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import kotlin.random.Random

class AudioContentKeyTest {

    @Test
    fun `given two indexing passes over the same bytes when keyed then the key is identical`() {
        val file = audioFile()

        assertEquals(keyOf(file), keyOf(file.copyOf()))
    }

    @Test
    fun `given a tag rewritten in place when keyed then the key is unchanged`() {
        val original = audioFile()
        // A tagger fixing a spelling rewrites inside the existing ID3v2 padding: the header
        // region changes, the payload and the total length do not.
        val retagged = original.copyOf().also { bytes ->
            for (index in 0 until TAG_REGION_BYTES) bytes[index] = 0x5A
        }

        assertEquals(keyOf(original), keyOf(retagged))
    }

    @Test
    fun `given the audio payload replaced when keyed then the key differs`() {
        val original = audioFile()
        val reEncoded = original.copyOf().also { bytes ->
            // One byte inside the first sampling window is enough.
            val sampledOffset = bytes.size / 4
            bytes[sampledOffset + 64] = (bytes[sampledOffset + 64] + 1).toByte()
        }

        assertNotEquals(keyOf(original), keyOf(reEncoded))
    }

    @Test
    fun `given a file truncated after the sampled windows when keyed then the key differs`() {
        val original = audioFile()

        // Nothing inside either window changes; only the size does. The size is part of the
        // key precisely so this counts as different audio.
        assertNotEquals(keyOf(original), keyOf(original.copyOf(original.size - 1)))
    }

    @Test
    fun `given an unreadable window when keyed then the key is empty`() {
        val key = AudioContentKey.derive(FILE_BYTES.toLong()) { _, _ -> null }

        assertEquals(AudioContentKey.UNAVAILABLE, key)
    }

    @Test
    fun `given a window that ends early when keyed then the key is empty`() {
        val key = AudioContentKey.derive(FILE_BYTES.toLong()) { _, length -> ByteArray(length - 1) }

        assertEquals(AudioContentKey.UNAVAILABLE, key)
    }

    @Test
    fun `given a provider reporting no size when keyed then the key is empty`() {
        val key = AudioContentKey.derive(0L) { _, length -> ByteArray(length) }

        assertEquals(AudioContentKey.UNAVAILABLE, key)
    }

    @Test
    fun `given a file smaller than two windows when keyed then it is digested whole`() {
        val tiny = Random(7).nextBytes(1_024)

        assertEquals(keyOf(tiny), keyOf(tiny.copyOf()))
        assertNotEquals(AudioContentKey.UNAVAILABLE, keyOf(tiny))
    }

    /** Derives the key for [bytes] as if it were a file of that exact size on disk. */
    private fun keyOf(bytes: ByteArray): String =
        AudioContentKey.derive(bytes.size.toLong()) { offset, length ->
            bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
        }

    /** Builds a deterministic stand-in for a tagged audio file large enough to be sampled. */
    private fun audioFile(): ByteArray = Random(42).nextBytes(FILE_BYTES)

    private companion object {
        /** Comfortably above [AudioContentKey.WHOLE_FILE_MAX_BYTES], so both windows apply. */
        const val FILE_BYTES = 1 shl 20

        /** Header region a tag edit touches, entirely below the 25% sampling offset. */
        const val TAG_REGION_BYTES = 8 * 1024
    }
}
