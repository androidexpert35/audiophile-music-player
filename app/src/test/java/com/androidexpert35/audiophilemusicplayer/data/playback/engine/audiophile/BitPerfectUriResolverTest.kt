package com.androidexpert35.audiophilemusicplayer.data.playback.engine.audiophile

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * Verifies the descriptor-ownership contract of the `content://` trampoline.
 *
 * The `/proc/self/fd/<n>` path a `content://` track resolves to is only valid
 * while the descriptor it names is open, and nothing else in the process closes
 * it — so a handle that leaks here costs one descriptor per track load, resume,
 * settings reload, and gapless preload.
 */
class BitPerfectUriResolverTest {

    private val descriptor = mockk<ParcelFileDescriptor>(relaxed = true)
    private val contentResolver = mockk<ContentResolver>()
    private val context = mockk<Context>()

    @Before
    fun setUpUriParsing() {
        mockkStatic(Uri::class)
        every { context.contentResolver } returns contentResolver
        every { descriptor.fd } returns TEST_FD
    }

    @After
    fun tearDownUriParsing() {
        unmockkStatic(Uri::class)
    }

    @Test
    fun `given content uri when resolved then descriptor is kept open behind its trampoline path`() {
        stubUri(CONTENT_URI, scheme = "content")
        every { contentResolver.openFileDescriptor(any(), "r") } returns descriptor

        val handle = resolveUriToSource(context, CONTENT_URI)

        assertEquals("/proc/self/fd/$TEST_FD", handle.path)
        verify(exactly = 0) { descriptor.close() }
    }

    @Test
    fun `given resolved content uri when handle is closed then the descriptor is released`() {
        stubUri(CONTENT_URI, scheme = "content")
        every { contentResolver.openFileDescriptor(any(), "r") } returns descriptor

        resolveUriToSource(context, CONTENT_URI).close()

        verify(exactly = 1) { descriptor.close() }
    }

    @Test
    fun `given resolved content uri when closed twice then the descriptor is released once`() {
        stubUri(CONTENT_URI, scheme = "content")
        every { contentResolver.openFileDescriptor(any(), "r") } returns descriptor

        val handle = resolveUriToSource(context, CONTENT_URI)
        handle.close()
        handle.close()

        // A descriptor number this process has already recycled must never be
        // closed a second time — it would belong to an unrelated owner by then.
        verify(exactly = 1) { descriptor.close() }
    }

    @Test
    fun `given file uri when resolved then the path is returned and no descriptor is opened`() {
        stubUri(FILE_URI, scheme = "file", path = "/storage/music/track.flac")

        val handle = resolveUriToSource(context, FILE_URI)
        handle.close()

        assertEquals("/storage/music/track.flac", handle.path)
        verify(exactly = 0) { contentResolver.openFileDescriptor(any(), any()) }
    }

    @Test
    fun `given unopenable content uri when resolved then the failure is reported and nothing is owned`() {
        stubUri(CONTENT_URI, scheme = "content")
        every { contentResolver.openFileDescriptor(any(), "r") } returns null

        assertThrows(IllegalStateException::class.java) {
            resolveUriToSource(context, CONTENT_URI)
        }
    }

    private fun stubUri(raw: String, scheme: String, path: String? = null) {
        val uri = mockk<Uri>(relaxed = true)
        every { uri.scheme } returns scheme
        every { uri.path } returns path
        every { Uri.parse(raw) } returns uri
    }

    private companion object {
        const val TEST_FD = 42
        const val CONTENT_URI = "content://media/external/audio/media/1234"
        const val FILE_URI = "file:///storage/music/track.flac"
    }
}
