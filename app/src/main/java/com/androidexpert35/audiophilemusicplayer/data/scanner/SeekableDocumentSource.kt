package com.androidexpert35.audiophilemusicplayer.data.scanner

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.EOFException
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Random-access reader over a document the user granted through a folder picker.
 *
 * DSD containers must be parsed by seeking to byte offsets recorded in their headers
 * (the DSF `fmt ` chunk, the ID3v2 tag offset, the DSDIFF chunk tree). Those files live
 * behind a document URI rather than a readable path — `READ_MEDIA_AUDIO` does not cover
 * `.dsf` / `.dff`, so `RandomAccessFile` cannot open them — and a plain `InputStream`
 * cannot seek. This wraps the descriptor's [FileChannel] to provide exactly the
 * positioned reads the parsers need.
 *
 * Instances own the underlying descriptor and must be closed; `use {}` is the intended
 * call pattern.
 *
 * @property stream Auto-closing stream that owns the descriptor and its channel.
 */
internal class SeekableDocumentSource private constructor(
    private val stream: FileInputStream,
) : Closeable {

    private val channel: FileChannel = stream.channel

    /** Current read position, mirroring `RandomAccessFile.getFilePointer()`. */
    val position: Long
        get() = runCatching { channel.position() }.getOrDefault(0L)

    /** Total document size in bytes, or `0` when the size cannot be determined. */
    fun length(): Long = runCatching { channel.size() }.getOrDefault(0L)

    /** Moves the read position to [newPosition] bytes from the start of the document. */
    fun seek(newPosition: Long) {
        channel.position(newPosition)
    }

    /**
     * Fills [buffer] completely, reading across as many channel reads as needed.
     *
     * @throws EOFException when the document ends before [buffer] is full.
     */
    fun readFully(buffer: ByteArray) {
        val target = ByteBuffer.wrap(buffer)
        while (target.hasRemaining()) {
            if (channel.read(target) < 0) throw EOFException("Unexpected end of DSD document")
        }
    }

    /**
     * Reads a single unsigned byte.
     *
     * @return Byte value in `0..255`, or `-1` at end of document.
     */
    fun readUnsignedByte(): Int {
        val single = ByteBuffer.allocate(1)
        return if (channel.read(single) < 0) -1 else single.get(0).toInt() and 0xFF
    }

    /** Advances the read position by [count] bytes without reading them. */
    fun skipBytes(count: Int) {
        if (count > 0) seek(position + count)
    }

    override fun close() {
        // Closing the auto-closing stream also closes the descriptor it was built from.
        runCatching { stream.close() }
    }

    companion object {
        /**
         * Opens [uri] for positioned reading.
         *
         * @param contentResolver Resolver holding the persisted read grant for the document.
         * @param uri Document URI inside a granted music folder.
         * @return An open source, or `null` when the document cannot be opened (grant
         *   revoked, volume unmounted, cloud-backed provider).
         */
        fun open(contentResolver: ContentResolver, uri: Uri): SeekableDocumentSource? =
            runCatching {
                val descriptor = contentResolver.openFileDescriptor(uri, "r") ?: return null
                SeekableDocumentSource(ParcelFileDescriptor.AutoCloseInputStream(descriptor))
            }.getOrNull()
    }
}
