package com.androidexpert35.audiophilemusicplayer.domain.repository

import com.androidexpert35.audiophilemusicplayer.domain.model.analysis.IntegralAnalysis
import com.androidexpert35.audiophilemusicplayer.domain.model.analysis.StationaryAnalysis
import com.androidexpert35.audiophilemusicplayer.domain.model.analysis.TrackAnalysis
import com.tony.coreui.domain.resource.Resource

/**
 * Stores and retrieves the measured signal analysis cached per piece of audio.
 *
 * Rows are addressed by the content key of the audio, so an analysis follows the
 * samples rather than the library row: it survives a re-index and is dropped when
 * the audio is replaced. An empty key is never stored — it means the file could
 * not be sampled at scan time, which is a "not analysable" outcome and not an
 * error.
 *
 * The two measurement classes are written independently. Persisting one never
 * disturbs the other, and reads only ever return rows written at the current
 * [TrackAnalysis.SCHEMA_VERSION]; anything older reads as absent so it is
 * recomputed instead of being trusted.
 */
interface TrackAnalysisRepository {

    /**
     * Reads the cached analysis for one piece of audio.
     *
     * @param audioKey Content key of the audio to look up.
     * @return [Resource.Success] carrying the cached row, or `null` inside it when
     *   nothing was measured, the key is blank, or the stored row predates the
     *   current schema version. [Resource.Error] only on a genuine storage failure.
     */
    suspend fun getAnalysis(audioKey: String): Resource<TrackAnalysis?>

    /**
     * Reads the cached analysis for the audio a library track currently points at.
     *
     * Callers that follow playback hold a track, not a content key, and only the
     * library index knows which audio a given track id resolves to today. Doing that
     * translation here keeps it out of the domain, which has no content key of its own
     * to resolve, and keeps a diagnostic read one call rather than two.
     *
     * @param trackId MediaStore identifier of the track being played or inspected.
     * @return [Resource.Success] carrying the cached row, or `null` inside it when the
     *   id is unknown to the index, the track carries no content key, or nothing was
     *   measured for it at the current [TrackAnalysis.SCHEMA_VERSION]. [Resource.Error]
     *   only on a genuine storage failure.
     */
    suspend fun getAnalysisForTrack(trackId: Long): Resource<TrackAnalysis?>

    /**
     * Persists the stationary measurements for one piece of audio.
     *
     * Leaves any integral measurements already cached at the current schema
     * version untouched, and discards the whole of an older row rather than
     * merging values whose meaning has since changed.
     *
     * @param audioKey Content key of the measured audio; a blank key is rejected.
     * @param stationary Measurements produced by the Class S sampling pass.
     * @return [Resource.Success] once the row is written, [Resource.Error] otherwise.
     */
    suspend fun saveStationaryAnalysis(
        audioKey: String,
        stationary: StationaryAnalysis
    ): Resource<Unit>

    /**
     * Persists the integral measurements for one piece of audio.
     *
     * The mirror of [saveStationaryAnalysis]: stationary values cached at the
     * current schema version survive the write.
     *
     * @param audioKey Content key of the measured audio; a blank key is rejected.
     * @param integral Measurements produced by a pass that saw the whole stream.
     * @return [Resource.Success] once the row is written, [Resource.Error] otherwise.
     */
    suspend fun saveIntegralAnalysis(
        audioKey: String,
        integral: IntegralAnalysis
    ): Resource<Unit>

    /**
     * Counts cached rows still waiting for a stationary pass.
     *
     * Rows written under an older schema version are counted as missing, because
     * that is what they are once their numbers stopped meaning what they did.
     *
     * @return [Resource.Success] carrying the count of rows without usable Class S
     *   measurements, [Resource.Error] on a storage failure.
     */
    suspend fun countMissingStationaryAnalysis(): Resource<Int>

    /**
     * Counts cached rows still waiting for a complete integral pass.
     *
     * @return [Resource.Success] carrying the count of rows without usable Class I
     *   measurements, [Resource.Error] on a storage failure.
     */
    suspend fun countMissingIntegralAnalysis(): Resource<Int>
}
