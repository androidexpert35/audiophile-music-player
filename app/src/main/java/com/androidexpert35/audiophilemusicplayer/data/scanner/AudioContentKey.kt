package com.androidexpert35.audiophilemusicplayer.data.scanner

import com.androidexpert35.audiophilemusicplayer.data.scanner.AudioContentKey.UNAVAILABLE
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * Decides whether two indexing passes are looking at the same audio, so a cached
 * per-track measurement can be reused across a re-index and discarded when the audio
 * itself is replaced.
 *
 * `TrackEntity.id` cannot answer that question: a MediaStore delete followed by a re-add
 * mints a new id for byte-identical audio, and a file overwritten in place keeps its id.
 * This derives a key from what the audio payload actually contains instead.
 *
 * **Why the file is sampled at fractional offsets and never from its start.**
 * The first kilobytes of an audio file are where ID3v2 and Vorbis comment blocks live.
 * Digesting them would make "fix a spelling in the artist tag" invalidate an analysis of
 * audio that never changed. Offsets at 25% and 75% of the file are, for any real track,
 * deep inside the encoded payload, so an in-place tag rewrite that leaves the file length
 * untouched — the usual case, because taggers rewrite inside the existing ID3v2 padding —
 * produces the same key.
 *
 * **What this deliberately does not survive.** A tag edit that changes the file's total
 * length (adding embedded artwork, growing past the padding) shifts every payload byte and
 * yields a different key. There is no offset scheme that hides that without decoding the
 * stream, and treating such a file as "changed" is the safe direction to be wrong in.
 *
 * The derivation is pure: it takes the size and a window reader, so it is exercised
 * directly in unit tests without any Android or filesystem involvement.
 */
internal object AudioContentKey {

    /**
     * Key value meaning "this file could not be sampled", never produced for a readable
     * file. It marks a track as not analysable; it is not an error signal.
     */
    const val UNAVAILABLE: String = ""

    /** Bytes read at each sampling offset. */
    const val WINDOW_BYTES: Int = 16 * 1024

    /**
     * Size at or below which the file is digested whole rather than sampled.
     *
     * Below this the two windows would overlap or run past the end, and a file this small
     * is not a track anyway — accepting that its key also covers its tag region costs
     * nothing real.
     */
    const val WHOLE_FILE_MAX_BYTES: Long = 4L * WINDOW_BYTES

    /** Marks the derivation scheme, so a future change to it can invalidate old keys. */
    private const val FORMAT_VERSION: String = "1"

    private const val DIGEST_ALGORITHM: String = "SHA-256"

    /** Digest prefix kept in the key: 128 bits, far beyond collision reach for a library. */
    private const val DIGEST_BYTES: Int = 16

    private const val HEX_DIGITS: String = "0123456789abcdef"

    /**
     * Builds the content key for a file of [fileSizeBytes] bytes.
     *
     * @param fileSizeBytes Total size of the file, from the scanner's provider metadata.
     * @param readWindow Reads exactly `length` bytes at `offset`, or returns `null` when the
     *   file cannot be read that far. Called at most twice.
     * @return The stable key, or [UNAVAILABLE] when the size is unknown or a window could
     *   not be read in full.
     */
    fun derive(
        fileSizeBytes: Long,
        readWindow: (offset: Long, length: Int) -> ByteArray?
    ): String {
        if (fileSizeBytes <= 0L) return UNAVAILABLE

        val digest = MessageDigest.getInstance(DIGEST_ALGORITHM)
        digest.update(FORMAT_VERSION.toByteArray(Charsets.US_ASCII))
        digest.update(encodeLong(fileSizeBytes))

        for ((offset, length) in sampleWindows(fileSizeBytes)) {
            if (length <= 0) return UNAVAILABLE
            val window = readWindow(offset, length) ?: return UNAVAILABLE
            if (window.size != length) return UNAVAILABLE
            // The offset is folded in so two windows holding the same bytes at different
            // positions cannot cancel out.
            digest.update(encodeLong(offset))
            digest.update(window)
        }

        return buildString {
            append(FORMAT_VERSION)
            append(':')
            append(fileSizeBytes.toString(RADIX_HEX))
            append(':')
            appendHex(digest.digest(), DIGEST_BYTES)
        }
    }

    /**
     * Picks the byte ranges that represent the audio payload of a file of this size.
     *
     * @param fileSizeBytes Total size of the file.
     * @return Offset-and-length pairs to digest, in read order.
     */
    private fun sampleWindows(fileSizeBytes: Long): List<Pair<Long, Int>> =
        if (fileSizeBytes <= WHOLE_FILE_MAX_BYTES) {
            listOf(0L to fileSizeBytes.toInt())
        } else {
            // Above WHOLE_FILE_MAX_BYTES a quarter of the file exceeds WINDOW_BYTES, so
            // both windows fit entirely between their offset and the end of the file.
            listOf(
                fileSizeBytes / 4L to WINDOW_BYTES,
                fileSizeBytes / 4L * 3L to WINDOW_BYTES,
            )
        }

    /**
     * Renders [value] as eight big-endian bytes so numbers enter the digest unambiguously.
     *
     * @param value Number to encode.
     * @return Its fixed-width big-endian representation.
     */
    private fun encodeLong(value: Long): ByteArray =
        ByteBuffer.allocate(Long.SIZE_BYTES).putLong(value).array()

    /**
     * Appends the first [count] bytes of [bytes] as lowercase hexadecimal.
     *
     * @param bytes Digest output to render.
     * @param count Number of leading bytes to include.
     */
    private fun StringBuilder.appendHex(bytes: ByteArray, count: Int) {
        for (index in 0 until minOf(count, bytes.size)) {
            val byte = bytes[index].toInt()
            append(HEX_DIGITS[(byte shr 4) and 0x0F])
            append(HEX_DIGITS[byte and 0x0F])
        }
    }

    private const val RADIX_HEX: Int = 16
}
