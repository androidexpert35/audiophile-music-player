package com.androidexpert35.audiophilemusicplayer.domain.model.library

/**
 * Persisted display choices for the library sections.
 *
 * @property sections Preferences keyed by a stable library section identifier.
 */
data class LibraryDisplayPreferences(
    val sections: Map<String, LibrarySectionDisplayPreference> = emptyMap(),
) {
    /** Returns the saved choice for [section], or the first-install defaults. */
    fun preferenceFor(section: String): LibrarySectionDisplayPreference =
        sections[section] ?: LibrarySectionDisplayPreference()
}

/**
 * Persisted sort and layout choice for one library section.
 *
 * @property sortOrder Name of the selected library sort strategy.
 * @property isGridView Whether the section uses the grid layout.
 * @property isVisible Whether the section is shown on the library screen's filter row.
 */
data class LibrarySectionDisplayPreference(
    val sortOrder: String = DEFAULT_SORT_ORDER,
    val isGridView: Boolean = false,
    val isVisible: Boolean = true,
) {
    companion object {
        /** Default sort used by a section with no saved preference. */
        const val DEFAULT_SORT_ORDER: String = "RECENTLY_ADDED"
    }
}
