package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioFormat
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track
import com.androidexpert35.audiophilemusicplayer.domain.repository.RecentlyPlayedRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ObserveMostPlayedTracksUseCaseTest {

    private val recentlyPlayedRepository = mockk<RecentlyPlayedRepository>()
    private val useCase = ObserveMostPlayedTracksUseCase(recentlyPlayedRepository)

    @Test
    fun `given persisted ranking when observed then tracks follow play count order`() = runTest {
        val tracks = listOf(sampleTrack(1L), sampleTrack(2L), sampleTrack(3L))
        every {
            recentlyPlayedRepository.observeMostPlayedTrackIds(
                trackIds = listOf(1L, 2L, 3L),
                limit = 5
            )
        } returns flowOf(listOf(3L, 1L, 999L))

        val result = useCase(tracks).first()

        assertEquals(listOf(tracks[2], tracks[0]), result)
    }

    @Test
    fun `given no candidate tracks when observed then repository is not queried`() = runTest {
        val result = useCase(emptyList()).first()

        assertEquals(emptyList<Track>(), result)
        verify(exactly = 0) {
            recentlyPlayedRepository.observeMostPlayedTrackIds(any(), any())
        }
    }

    private fun sampleTrack(id: Long): Track = Track(
        id = id,
        title = "Track $id",
        artistName = "Artist",
        albumTitle = "Album",
        albumId = 10L,
        durationMs = 180_000L,
        uri = "content://tracks/$id",
        trackNumber = id.toInt(),
        discNumber = 1,
        audioFormat = AudioFormat.UNKNOWN,
        fileSizeBytes = 1L,
        dateAdded = id
    )
}
