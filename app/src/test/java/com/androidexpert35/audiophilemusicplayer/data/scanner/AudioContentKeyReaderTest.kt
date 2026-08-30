package com.androidexpert35.audiophilemusicplayer.data.scanner

import android.content.ContentResolver
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class AudioContentKeyReaderTest {

    private val contentResolver = mockk<ContentResolver>()
    private val reader = AudioContentKeyReader(contentResolver)

    @Test
    fun `given a document the provider will not open when keyed then the key is empty`() {
        every { contentResolver.openFileDescriptor(any(), "r") } returns null

        assertEquals(AudioContentKey.UNAVAILABLE, reader.read(mockk<Uri>(), 4_194_304L))
    }

    @Test
    fun `given a revoked grant when keyed then the failure is absorbed rather than thrown`() {
        every { contentResolver.openFileDescriptor(any(), "r") } throws SecurityException("revoked")

        assertEquals(AudioContentKey.UNAVAILABLE, reader.read(mockk<Uri>(), 4_194_304L))
    }
}
