package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.common

import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioFormat
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track
import com.tony.coreui.data.strings.CoreUiStringProvider

/**
 * Precomputed audio quality summary derived from a collection of tracks.
 *
 * Intended as a display-layer value type computed once per model update so
 * individual Composables can read derived properties without recalculating on
 * every recomposition.
 *
 * @property losslessTrackCount Number of tracks whose [AudioFormat.isLossless] flag is `true`.
 * @property hiResTrackCount Number of tracks qualifying as hi-resolution audio
 *   (sample rate ≥ 88 200 Hz **or** bit depth ≥ 24 bits).
 * @property maxSampleRateHz Highest sample rate found across all tracks, or `0` if the
 *   list is empty or no sample-rate metadata is present.
 * @property maxBitDepth Highest bit depth found across all tracks, or `0` if the list
 *   is empty or no bit-depth metadata is present.
 * @property codecSummary Human-readable codec enumeration, e.g. `"FLAC • AAC"`.
 *   Blank or `"Unknown"` codec display names are filtered out before building this string.
 *   Defaults to `"Unknown"` when no recognizable codec is present.
 */
data class TrackListStats(
    val losslessTrackCount: Int,
    val hiResTrackCount: Int,
    val maxSampleRateHz: Int,
    val maxBitDepth: Int,
    val codecSummary: String
)

/**
 * Computes an audio quality summary for this track collection in a single pass.
 *
 * Codec display names that are blank or equal to `"Unknown"` are excluded from the
 * [TrackListStats.codecSummary] so the result only contains meaningful identifiers.
 * The caller is responsible for wrapping this call in `remember(tracks)` inside a
 * Composable to avoid redundant computation on every recomposition.
 *
 * @return A [TrackListStats] snapshot capturing the quality profile of this collection.
 */
fun List<Track>.computeAudioStats(): TrackListStats {
    val unknownLabel = CoreUiStringProvider.get(R.string.common_unknown)
    val knownCodecs = map { it.audioFormat.codec.displayName }
        .filter { name -> name.isNotBlank() && name != unknownLabel }
        .distinct()

    return TrackListStats(
        losslessTrackCount = count { it.audioFormat.isLossless },
        hiResTrackCount = count { it.audioFormat.sampleRateHz >= 88_200 || it.audioFormat.bitDepth >= 24 },
        maxSampleRateHz = maxOfOrNull { it.audioFormat.sampleRateHz } ?: 0,
        maxBitDepth = maxOfOrNull { it.audioFormat.bitDepth } ?: 0,
        codecSummary = knownCodecs.takeIf { it.isNotEmpty() }?.joinToString(" • ") ?: unknownLabel
    )
}

