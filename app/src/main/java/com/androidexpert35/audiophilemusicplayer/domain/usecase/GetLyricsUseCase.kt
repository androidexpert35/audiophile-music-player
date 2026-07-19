package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.model.lyrics.Lyrics
import com.androidexpert35.audiophilemusicplayer.domain.repository.LyricsRepository
import com.tony.coreui.domain.resource.Resource

/**
 * Retrieves synchronized (or plain-text) lyrics for a specific track.
 *
 * Delegates entirely to [lyricsRepository], which handles caching and network
 * lookups transparently. The use case holds no state of its own.
 *
 * This class has no `@Inject` constructor — it is provided via
 * [com.androidexpert35.audiophilemusicplayer.di.UseCaseModule] so that the
 * Domain layer remains free of Android / DI framework annotations.
 *
 * @property lyricsRepository Repository sourcing lyrics from the remote API and
 *   the local Room cache.
 * @constructor Creates the use case with its required repository dependency.
 */
class GetLyricsUseCase(
    private val lyricsRepository: LyricsRepository,
) {

    /**
     * Fetches lyrics for the given track metadata.
     *
     * @param trackTitle Display title of the track.
     * @param artistName Performing artist name.
     * @param albumName Album title.
     * @param durationSeconds Track duration rounded to the nearest second.
     * @return [Resource.Success] with the resolved [Lyrics] on success,
     *         [Resource.Error] on network or storage failure.
     * @see LyricsRepository.getLyrics
     */
    suspend operator fun invoke(
        trackTitle: String,
        artistName: String,
        albumName: String,
        durationSeconds: Int,
    ): Resource<Lyrics> = lyricsRepository.getLyrics(
        trackTitle = trackTitle,
        artistName = artistName,
        albumName = albumName,
        durationSeconds = durationSeconds,
    )
}

