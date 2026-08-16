package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library

import androidx.compose.runtime.Immutable
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track
import java.util.Locale

/**
 * A playable metadata grouping shown by the Genres, Years, or Composers section.
 *
 * @property name User-visible metadata value shared by [tracks].
 * @property tracks Indexed tracks carrying this metadata value.
 */
@Immutable
data class LibraryFacet(
    val name: String,
    val tracks: List<Track>,
) {
    /** Stable section-local key suitable for Compose lazy item identity. */
    val key: String = name.lowercase(Locale.ROOT)

    /** Number of playable tracks represented by this facet. */
    val trackCount: Int = tracks.size
}

/**
 * Immutable route-safe description of one metadata collection in the library.
 *
 * @property section Metadata section that owns [name]. Only Genres, Years, and
 * Composers are valid section values.
 * @property name Exact display value selected by the listener.
 */
@Immutable
data class LibraryFacetFilter(
    val section: LibraryContentType,
    val name: String,
) {
    /** Returns whether [track] belongs to this metadata collection. */
    fun matches(track: Track): Boolean = when (section) {
        LibraryContentType.GENRES -> track.genre.splitFacetValues().any { it.equals(name, ignoreCase = true) }
        LibraryContentType.COMPOSERS -> track.composer.splitFacetValues().any { it.equals(name, ignoreCase = true) }
        LibraryContentType.YEARS -> track.year > 0 && track.year.toString() == name
        else -> false
    }

    companion object {
        /** Rebuilds a validated metadata filter from the two navigation path arguments. */
        fun fromRoute(sectionName: String, name: String): LibraryFacetFilter? {
            val section = LibraryContentType.entries.firstOrNull { it.name == sectionName }
                ?.takeIf { it in metadataSections }
                ?: return null
            return LibraryFacetFilter(section = section, name = name)
        }

        private val metadataSections = setOf(
            LibraryContentType.GENRES,
            LibraryContentType.YEARS,
            LibraryContentType.COMPOSERS,
        )
    }
}

/** Builds genre facets from every non-blank genre tag in the indexed catalogue. */
internal fun List<Track>.toGenreFacets(): List<LibraryFacet> =
    toTextFacets { track -> track.genre.splitFacetValues() }

/** Builds composer facets from every non-blank composer tag in the indexed catalogue. */
internal fun List<Track>.toComposerFacets(): List<LibraryFacet> =
    toTextFacets { track -> track.composer.splitFacetValues() }

/** Builds descending release-year facets from every track with a known year. */
internal fun List<Track>.toYearFacets(): List<LibraryFacet> =
    filter { it.year > 0 }
        .groupBy { it.year }
        .toSortedMap(compareByDescending { it })
        .map { (year, tracks) -> LibraryFacet(name = year.toString(), tracks = tracks) }

private fun List<Track>.toTextFacets(values: (Track) -> List<String>): List<LibraryFacet> =
    flatMap { track -> values(track).map { value -> value to track } }
        .groupBy { (value, _) -> value.lowercase(Locale.ROOT) }
        .values
        .map { matches ->
            LibraryFacet(
                name = matches.first().first,
                tracks = matches.map { (_, track) -> track }.distinctBy(Track::id),
            )
        }
        .sortedBy { it.name.lowercase(Locale.ROOT) }

internal fun String?.splitFacetValues(): List<String> =
    this.orEmpty()
        .split(';', '|')
        .map(String::trim)
        .filter(String::isNotEmpty)
