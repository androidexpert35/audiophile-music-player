package com.androidexpert35.audiophilemusicplayer.data.repository

import com.androidexpert35.audiophilemusicplayer.data.playback.PlaybackController
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioFormat
import com.androidexpert35.audiophilemusicplayer.domain.model.common.PlaybackResourceError
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track
import com.tony.coreui.domain.resource.Resource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [PlaybackRepositoryImpl].
 *
 * Verifies that playback commands are delegated to [PlaybackController] and
 * that controller failures are surfaced as [PlaybackResourceError]
 * rather than disappearing silently.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackRepositoryImplTest {

    private val playbackController = mockk<PlaybackController>()

    private val repository = PlaybackRepositoryImpl(
        playbackController = playbackController
    )

    @Test
    fun `given controller starts playback when play invoked then repository returns success`() = runTest {
        val track = sampleTrack(id = 1L)
        val queue = listOf(track, sampleTrack(id = 2L))
        coEvery { playbackController.play(track, queue) } returns Unit

        val result = repository.play(track, queue)

        assertTrue(result is Resource.Success)
        coVerify(exactly = 1) { playbackController.play(track, queue) }
    }

    @Test
    fun `given controller connection fails when play invoked then repository returns playback error`() = runTest {
        val track = sampleTrack(id = 1L)
        val queue = listOf(track)
        coEvery { playbackController.play(track, queue) } throws IllegalStateException("Failed to connect to the playback service.")

        val result = repository.play(track, queue)

        assertTrue(result is Resource.Error)
        assertEquals(
            PlaybackResourceError("Failed to connect to the playback service."),
            (result as Resource.Error).data
        )
    }

    @Test
    fun `given track when play next invoked then repository delegates queue insertion`() = runTest {
        val track = sampleTrack(id = 3L)
        coEvery { playbackController.playNext(track) } returns Unit

        val result = repository.playNext(track)

        assertTrue(result is Resource.Success)
        coVerify(exactly = 1) { playbackController.playNext(track) }
    }

    @Test
    fun `given track when add to queue invoked then repository delegates append`() = runTest {
        val track = sampleTrack(id = 4L)
        coEvery { playbackController.addToQueue(track) } returns Unit

        val result = repository.addToQueue(track)

        assertTrue(result is Resource.Success)
        coVerify(exactly = 1) { playbackController.addToQueue(track) }
    }

    @Test
    fun `given ordered tracks when play next invoked then repository delegates one batch insertion`() = runTest {
        val tracks = listOf(sampleTrack(id = 5L), sampleTrack(id = 6L))
        coEvery { playbackController.playNext(tracks) } returns Unit

        val result = repository.playNext(tracks)

        assertTrue(result is Resource.Success)
        coVerify(exactly = 1) { playbackController.playNext(tracks) }
        coVerify(exactly = 0) { playbackController.playNext(any<Track>()) }
    }

    @Test
    fun `given ordered tracks when add to queue invoked then repository delegates one batch append`() = runTest {
        val tracks = listOf(sampleTrack(id = 7L), sampleTrack(id = 8L))
        coEvery { playbackController.addToQueue(tracks) } returns Unit

        val result = repository.addToQueue(tracks)

        assertTrue(result is Resource.Success)
        coVerify(exactly = 1) { playbackController.addToQueue(tracks) }
        coVerify(exactly = 0) { playbackController.addToQueue(any<Track>()) }
    }

    @Test
    fun `given queue positions when move invoked then repository delegates reorder`() = runTest {
        coEvery { playbackController.moveQueueItem(3, 1) } returns Unit

        val result = repository.moveQueueItem(3, 1)

        assertTrue(result is Resource.Success)
        coVerify(exactly = 1) { playbackController.moveQueueItem(3, 1) }
    }

    @Test
    fun `given active queue when clear invoked then repository delegates queue removal`() = runTest {
        coEvery { playbackController.clearQueue() } returns Unit

        val result = repository.clearQueue()

        assertTrue(result is Resource.Success)
        coVerify(exactly = 1) { playbackController.clearQueue() }
    }

    @Test
    fun `given controller rejects queue mutation when invoked then repository returns playback error`() = runTest {
        val track = sampleTrack(id = 5L)
        coEvery { playbackController.playNext(track) } throws IllegalArgumentException("Queue is unavailable.")

        val result = repository.playNext(track)

        assertTrue(result is Resource.Error)
        assertEquals(
            PlaybackResourceError("Queue is unavailable."),
            (result as Resource.Error).data
        )
    }

    /**
     * Creates a compact but realistic domain track used for repository tests.
     *
     * @param id Stable identifier used to distinguish queue items.
     * @return Track configured with a playable local content URI.
     */
    private fun sampleTrack(id: Long): Track = Track(
        id = id,
        title = "Track $id",
        artistName = "Artist",
        albumTitle = "Album",
        albumId = 99L,
        durationMs = 180_000L,
        uri = "content://media/external/audio/media/$id",
        trackNumber = id.toInt(),
        discNumber = 1,
        audioFormat = AudioFormat.UNKNOWN,
        fileSizeBytes = 12_000_000L,
        dateAdded = 0L
    )
}
