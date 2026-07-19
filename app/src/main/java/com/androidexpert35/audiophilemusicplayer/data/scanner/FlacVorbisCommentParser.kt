package com.androidexpert35.audiophilemusicplayer.data.scanner

import java.io.InputStream

/**
 * Extracts the release year from a FLAC Vorbis-comment metadata block.
 *
 * Android's MediaStore and [android.media.MediaMetadataRetriever] do not
 * consistently surface the Vorbis `DATE` field. This small parser reads only FLAC
 * metadata blocks, never audio frames, so it is suitable for the indexing fallback
 * path and does not add a media-decoding dependency.
 */
internal object FlacVorbisCommentParser {

    private const val FLAC_MARKER = "fLaC"
    private const val VORBIS_COMMENT_BLOCK_TYPE = 4
    private const val MAX_METADATA_BLOCK_SIZE_BYTES = 16 * 1024 * 1024
    private val yearRegex = Regex("""(?<!\d)(\d{4})(?!\d)""")

    /**
     * Reads `DATE`, `YEAR`, or `ORIGINALDATE` from a FLAC stream.
     *
     * @return A four-digit release year, or `null` when the stream is not FLAC,
     *   contains no usable Vorbis comment, or cannot be read safely.
     */
    fun readYear(input: InputStream): Int? = runCatching {
        val marker = ByteArray(FLAC_MARKER.length)
        if (!input.readFully(marker) || marker.decodeToString() != FLAC_MARKER) return null

        var isLastMetadataBlock = false
        while (!isLastMetadataBlock) {
            val header = input.read()
            if (header == -1) return null

            isLastMetadataBlock = header and 0x80 != 0
            val blockType = header and 0x7F
            val blockSize = input.readUnsigned24BitBigEndian() ?: return null
            if (blockSize > MAX_METADATA_BLOCK_SIZE_BYTES) return null

            if (blockType == VORBIS_COMMENT_BLOCK_TYPE) {
                val vorbisCommentBlock = input.readExactly(blockSize) ?: return null
                return parseYear(vorbisCommentBlock)
            }
            if (!input.skipExactly(blockSize)) return null
        }
        null
    }.getOrNull()

    /** Returns a four-digit year from a Vorbis date value such as `1997-08-25`. */
    internal fun parseYear(value: String?): Int? = value
        ?.let(yearRegex::find)
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
        ?.takeIf { it in 1000..9999 }

    private fun parseYear(block: ByteArray): Int? {
        var offset = 0
        val vendorLength = block.readUnsigned32BitLittleEndian(offset) ?: return null
        offset += Int.SIZE_BYTES
        if (vendorLength > block.size - offset) return null
        offset += vendorLength

        val commentCount = block.readUnsigned32BitLittleEndian(offset) ?: return null
        offset += Int.SIZE_BYTES
        if (commentCount > MAX_COMMENT_COUNT) return null

        val dateValues = mutableMapOf<String, String>()
        repeat(commentCount) {
            val commentLength = block.readUnsigned32BitLittleEndian(offset) ?: return null
            offset += Int.SIZE_BYTES
            if (commentLength > block.size - offset) return null

            val comment = block.decodeToString(offset, offset + commentLength)
            offset += commentLength
            val separatorIndex = comment.indexOf('=')
            if (separatorIndex > 0) {
                val key = comment.substring(0, separatorIndex).uppercase()
                if (key in YEAR_KEYS) {
                    dateValues.putIfAbsent(key, comment.substring(separatorIndex + 1))
                }
            }
        }

        return YEAR_KEYS.firstNotNullOfOrNull { key -> parseYear(dateValues[key]) }
    }

    private fun InputStream.readFully(destination: ByteArray): Boolean {
        var offset = 0
        while (offset < destination.size) {
            val read = read(destination, offset, destination.size - offset)
            if (read <= 0) return false
            offset += read
        }
        return true
    }

    private fun InputStream.readExactly(size: Int): ByteArray? {
        val bytes = ByteArray(size)
        return bytes.takeIf { readFully(it) }
    }

    private fun InputStream.readUnsigned24BitBigEndian(): Int? {
        val first = read()
        val second = read()
        val third = read()
        if (first == -1 || second == -1 || third == -1) return null
        return (first shl 16) or (second shl 8) or third
    }

    private fun InputStream.skipExactly(size: Int): Boolean {
        var remaining = size.toLong()
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else if (read() == -1) {
                return false
            } else {
                remaining--
            }
        }
        return true
    }

    private fun ByteArray.readUnsigned32BitLittleEndian(offset: Int): Int? {
        if (offset > size - Int.SIZE_BYTES) return null
        return (this[offset].toInt() and 0xFF) or
            ((this[offset + 1].toInt() and 0xFF) shl 8) or
            ((this[offset + 2].toInt() and 0xFF) shl 16) or
            ((this[offset + 3].toInt() and 0x7F) shl 24)
    }

    private const val MAX_COMMENT_COUNT = 10_000
    private val YEAR_KEYS = listOf("DATE", "YEAR", "ORIGINALDATE")
}
