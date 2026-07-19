package com.androidexpert35.audiophilemusicplayer.domain.model.track

/**
 * Extracts individually navigable artist names from a track-level artist credit.
 *
 * MediaStore artist fields frequently contain collaboration text such as
 * "Artist A feat. Artist B" or "Artist A & Artist B". The player uses these
 * parsed names to offer direct navigation targets for the album artist and for
 * each featured collaborator.
 *
 * The parser intentionally normalizes whitespace, strips empty fragments, and
 * de-duplicates names case-insensitively while preserving the original display
 * capitalization of the first occurrence.
 *
 * @param artistDisplayName Raw artist credit read from indexed media metadata.
 * @return Ordered list of navigable artist names. Returns an empty list when the
 * credit is blank.
 */
fun extractNavigableArtistNames(artistDisplayName: String): List<String> {
    val normalizedCredit = artistDisplayName
        .replace(WHITESPACE_REGEX, " ")
        .trim()

    if (normalizedCredit.isBlank()) {
        return emptyList()
    }

    val parsedArtists = FEATURE_SEPARATOR_REGEX
        .split(normalizedCredit)
        .flatMap { section ->
            COLLABORATOR_SEPARATOR_REGEX.split(section)
        }
        .map { artist -> artist.trim().trim('-', '–', '—') }
        .filter { artist -> artist.isNotBlank() }

    if (parsedArtists.isEmpty()) {
        return listOf(normalizedCredit)
    }

    return parsedArtists.distinctBy { artist -> artist.lowercase() }
}

/**
 * Determines whether a track-level artist credit contains the requested artist.
 *
 * Matching is performed against the normalized list returned by
 * [extractNavigableArtistNames], ensuring that featured-artist navigation can
 * resolve tracks whose artist field contains multiple collaborators.
 *
 * @param artistDisplayName Raw track-level artist credit.
 * @param artistName Artist name selected by the user for navigation.
 * @return `true` when the parsed artist choices contain the requested artist,
 * ignoring case; otherwise `false`.
 */
fun containsNavigableArtist(
    artistDisplayName: String,
    artistName: String
): Boolean {
    val normalizedArtistName = artistName.trim()
    if (normalizedArtistName.isBlank()) {
        return false
    }

    return extractNavigableArtistNames(artistDisplayName)
        .any { candidate -> candidate.equals(normalizedArtistName, ignoreCase = true) }
}

private val WHITESPACE_REGEX = Regex("""\s+""")
private val FEATURE_SEPARATOR_REGEX = Regex(
    pattern = """\s+(?:feat\.?|featuring|ft\.?|with)\s+""",
    option = RegexOption.IGNORE_CASE
)
private val COLLABORATOR_SEPARATOR_REGEX = Regex(
    pattern = """\s*(?:,|&|/|;|\bx\b|\band\b)\s*""",
    option = RegexOption.IGNORE_CASE
)
