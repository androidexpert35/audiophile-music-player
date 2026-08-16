package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings

import androidx.lifecycle.viewModelScope
import com.androidexpert35.audiophilemusicplayer.domain.model.library.LibraryDisplayPreferences
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveLibraryDisplayPreferencesUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveLibrarySectionOrderUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.SetLibraryDisplayPreferencesUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.SetLibrarySectionOrderUseCase
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library.LibraryContentType
import com.tony.coreui.data.strings.StringResolver
import com.tony.coreui.presentation.error.UiErrorMapper
import com.tony.coreui.presentation.navigation.NavigationManager
import com.tony.coreui.presentation.viewmodel.BaseViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel powering the Library Sections settings sub-screen.
 *
 * @property observeLibraryDisplayPreferencesUseCase Source of truth for section visibility
 *   (and the sort/grid choices this screen must preserve untouched when it persists a
 *   visibility change).
 * @property observeLibrarySectionOrderUseCase Source of truth for section display order.
 * @property setLibraryDisplayPreferencesUseCase Writes the full display-preferences map.
 * @property setLibrarySectionOrderUseCase Writes the section display order.
 */
@HiltViewModel
class LibrarySectionsSettingsViewModel @Inject constructor(
    observeLibraryDisplayPreferencesUseCase: ObserveLibraryDisplayPreferencesUseCase,
    observeLibrarySectionOrderUseCase: ObserveLibrarySectionOrderUseCase,
    private val setLibraryDisplayPreferencesUseCase: SetLibraryDisplayPreferencesUseCase,
    private val setLibrarySectionOrderUseCase: SetLibrarySectionOrderUseCase,
    navigationManager: NavigationManager,
    stringResolver: StringResolver,
    uiErrorMapper: UiErrorMapper
) : BaseViewModel<LibrarySectionsSettingsUiModel, LibrarySectionsSettingsUiEvent, LibrarySectionsSettingsUiEffect>(
    navigationManager = navigationManager,
    stringResolver = stringResolver,
    uiErrorMapper = uiErrorMapper
) {

    /** Latest full preferences snapshot, kept so a visibility write never drops another section's sort/grid choice. */
    private var latestPreferences: LibraryDisplayPreferences = LibraryDisplayPreferences()

    init {
        setSuccessState(LibrarySectionsSettingsUiModel())
        combine(
            observeLibraryDisplayPreferencesUseCase(),
            observeLibrarySectionOrderUseCase(),
        ) { preferences, order ->
            latestPreferences = preferences
            val orderedTypes = order.mapNotNull { name ->
                LibraryContentType.entries.firstOrNull { it.name == name }
            }
            val missingTypes = LibraryContentType.entries.filterNot { it in orderedTypes }
            (orderedTypes + missingTypes).map { contentType ->
                LibrarySectionRow(
                    section = contentType,
                    isVisible = preferences.preferenceFor(contentType.name).isVisible,
                )
            }
        }
            .onEach { rows -> setSuccessState(LibrarySectionsSettingsUiModel(rows = rows)) }
            .launchIn(viewModelScope)
    }

    override fun handleEvent(event: LibrarySectionsSettingsUiEvent) {
        when (event) {
            is LibrarySectionsSettingsUiEvent.ToggleVisibility -> handleToggleVisibility(event.section)
            is LibrarySectionsSettingsUiEvent.MoveSection -> handleMoveSection(event.fromIndex, event.toIndex)
        }
    }

    private fun handleToggleVisibility(section: LibraryContentType) {
        val current = uiState.value.data ?: return
        val visibleCount = current.rows.count { it.isVisible }
        val row = current.rows.firstOrNull { it.section == section } ?: return
        // Never let the last visible section be hidden — the Library screen must
        // always have at least one section to show.
        if (row.isVisible && visibleCount <= 1) return

        val newVisibility = !row.isVisible
        val updatedRows = current.rows.map {
            if (it.section == section) it.copy(isVisible = newVisibility) else it
        }
        setSuccessState(current.copy(rows = updatedRows))

        val existingPreference = latestPreferences.preferenceFor(section.name)
        val updatedPreferences = latestPreferences.copy(
            sections = latestPreferences.sections + (
                section.name to existingPreference.copy(isVisible = newVisibility)
            )
        )
        viewModelScope.launch {
            setLibraryDisplayPreferencesUseCase(updatedPreferences)
        }
    }

    private fun handleMoveSection(fromIndex: Int, toIndex: Int) {
        val current = uiState.value.data ?: return
        if (fromIndex == toIndex ||
            fromIndex !in current.rows.indices ||
            toIndex !in current.rows.indices
        ) {
            return
        }

        val reorderedRows = current.rows.toMutableList().apply {
            add(toIndex, removeAt(fromIndex))
        }
        setSuccessState(current.copy(rows = reorderedRows))

        viewModelScope.launch {
            setLibrarySectionOrderUseCase(reorderedRows.map { it.section.name })
        }
    }
}
