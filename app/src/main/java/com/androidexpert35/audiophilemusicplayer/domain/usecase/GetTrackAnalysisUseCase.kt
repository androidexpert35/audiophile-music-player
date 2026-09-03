package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.model.analysis.TrackAnalysis
import com.androidexpert35.audiophilemusicplayer.domain.repository.TrackAnalysisRepository
import com.tony.coreui.domain.resource.Resource

/**
 * Reads whatever the analysis cache already holds about one track's audio.
 *
 * Purely a lookup: it never triggers a measurement, so a track that has not been
 * analysed simply reports nothing and the caller says so. That is what makes it
 * safe to call while a track is playing — the answer costs one indexed row read
 * on the IO dispatcher and can never reach a decoder or the audio thread.
 *
 * @property trackAnalysisRepository Store of cached per-audio measurements.
 */
class GetTrackAnalysisUseCase(
    private val trackAnalysisRepository: TrackAnalysisRepository
) {
    /**
     * @param trackId MediaStore identifier of the track to look up.
     * @return [Resource.Success] carrying the cached analysis, or `null` inside it when
     *   the audio has not been measured at the current schema version. [Resource.Error]
     *   only on a storage failure.
     */
    suspend operator fun invoke(trackId: Long): Resource<TrackAnalysis?> =
        trackAnalysisRepository.getAnalysisForTrack(trackId)
}
