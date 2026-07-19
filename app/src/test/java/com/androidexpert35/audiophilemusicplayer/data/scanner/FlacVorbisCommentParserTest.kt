package com.androidexpert35.audiophilemusicplayer.data.scanner

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FlacVorbisCommentParserTest {

    @Test
    fun `given FLAC DATE in ISO form when parsed then release year is returned`() {
        val flac = flacWithVorbisComments("DATE=1997-08-25")

        assertEquals(1997, FlacVorbisCommentParser.readYear(ByteArrayInputStream(flac)))
    }

    @Test
    fun `given FLAC YEAR when parsed then release year is returned`() {
        val flac = flacWithVorbisComments("YEAR=2024")

        assertEquals(2024, FlacVorbisCommentParser.readYear(ByteArrayInputStream(flac)))
    }

    @Test
    fun `given FLAC without year comment when parsed then null is returned`() {
        val flac = flacWithVorbisComments("ARTIST=Artist", "ALBUM=Album")

        assertNull(FlacVorbisCommentParser.readYear(ByteArrayInputStream(flac)))
    }

    private fun flacWithVorbisComments(vararg comments: String): ByteArray {
        val vorbisBlock = ByteArrayOutputStream().apply {
            val vendor = "Audiophile".encodeToByteArray()
            write(littleEndianInt(vendor.size))
            write(vendor)
            write(littleEndianInt(comments.size))
            comments.forEach { comment ->
                val bytes = comment.encodeToByteArray()
                write(littleEndianInt(bytes.size))
                write(bytes)
            }
        }.toByteArray()

        return ByteArrayOutputStream().apply {
            write("fLaC".encodeToByteArray())
            write(0x80 or 4) // Last metadata block + VORBIS_COMMENT.
            write(byteArrayOf(
                (vorbisBlock.size ushr 16).toByte(),
                (vorbisBlock.size ushr 8).toByte(),
                vorbisBlock.size.toByte()
            ))
            write(vorbisBlock)
        }.toByteArray()
    }

    private fun littleEndianInt(value: Int): ByteArray = byteArrayOf(
        value.toByte(),
        (value ushr 8).toByte(),
        (value ushr 16).toByte(),
        (value ushr 24).toByte()
    )
}
