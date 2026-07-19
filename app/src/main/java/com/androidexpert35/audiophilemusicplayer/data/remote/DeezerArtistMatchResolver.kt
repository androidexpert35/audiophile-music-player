package com.androidexpert35.audiophilemusicplayer.data.remote

import com.androidexpert35.audiophilemusicplayer.data.remote.dto.DeezerArtistDto
import java.text.Normalizer
import java.util.Locale

/**
 * Selects the safest Deezer artist result for a local-library artist name.
 *
 * Deezer search ranking alone is not a reliable identity check: the first result
 * can be a similarly named artist. Only normalised exact-name matches are accepted,
 * and popularity is used solely to disambiguate multiple exact matches.
 *
 * @param artistName Artist credit stored in the local media index.
 * @param candidates Artist records returned by Deezer search.
 * @return The most popular exact-name candidate with usable artwork, or `null`.
 */
internal fun resolveDeezerArtistMatch(
    artistName: String,
    candidates: List<DeezerArtistDto>
): DeezerArtistDto? {
    val normalizedTarget = artistName.toArtistMatchKey()
    if (normalizedTarget.isBlank()) return null

    return candidates
        .asSequence()
        .filter { candidate -> candidate.name.toArtistMatchKey() == normalizedTarget }
        .filter { candidate ->
            !candidate.pictureXl.isNullOrBlank() || !candidate.pictureBig.isNullOrBlank()
        }
        .maxWithOrNull(
            compareBy<DeezerArtistDto> { candidate -> candidate.fanCount }
                .thenBy { candidate -> candidate.id }
        )
}

/**
 * Builds a locale-stable comparison key for artist identity matching.
 *
 * Diacritics, punctuation, and spacing differences are ignored so equivalent
 * credits such as `Beyoncé` / `Beyonce` and `AC/DC` / `ACDC` still match, while
 * added words such as `tribute` can never pass as the requested artist.
 */
private fun String.toArtistMatchKey(): String =
    Normalizer.normalize(trim(), Normalizer.Form.NFKD)
        .lowercase(Locale.ROOT)
        .filter(Char::isLetterOrDigit)
