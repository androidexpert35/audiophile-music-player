package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings

import com.androidexpert35.audiophilemusicplayer.FakeNavigationManager
import com.androidexpert35.audiophilemusicplayer.MainDispatcherRule
import com.androidexpert35.audiophilemusicplayer.TestStringResolver
import com.androidexpert35.audiophilemusicplayer.TestUiErrorMapper
import com.androidexpert35.audiophilemusicplayer.domain.model.library.LibraryDisplayPreferences
import com.androidexpert35.audiophilemusicplayer.domain.model.library.LibrarySectionDisplayPreference
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveLibraryDisplayPreferencesUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.ObserveLibrarySectionOrderUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.SetLibraryDisplayPreferencesUseCase
import com.androidexpert35.audiophilemusicplayer.domain.usecase.SetLibrarySectionOrderUseCase
import com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.library.LibraryContentType
import com.tony.coreui.domain.resource.Resource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibrarySectionsSettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val observeLibraryDisplayPreferencesUseCase = mockk<ObserveLibraryDisplayPreferencesUseCase>()
    private val observeLibrarySectionOrderUseCase = mockk<ObserveLibrarySectionOrderUseCase>()
    private val setLibraryDisplayPreferencesUseCase = mockk<SetLibraryDisplayPreferencesUseCase>()
    private val setLibrarySectionOrderUseCase = mockk<SetLibrarySectionOrderUseCase>()
    private val navigationManager = FakeNavigationManager()

    private val preferencesFlow = MutableStateFlow(LibraryDisplayPreferences())
    private val orderFlow = MutableStateFlow(LibraryContentType.entries.map { it.name })

    @Test
    fun `given default preferences when observed then every section is listed visible in saved order`() = runTest {
        stubObservationUseCases()
        val viewModel = createViewModel()
        advanceUntilIdle()

        val rows = viewModel.uiState.value.data?.rows
        assertEquals(
            LibraryContentType.entries.toList(),
            rows?.map { it.section }
        )
        assertEquals(List(LibraryContentType.entries.size) { true }, rows?.map { it.isVisible })
    }

    @Test
    fun `given a visible section when hidden then preferences are persisted with that section invisible`() = runTest {
        stubObservationUseCases()
        coEvery { setLibraryDisplayPreferencesUseCase.invoke(any()) } returns Resource.Success(Unit)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onEvent(LibrarySectionsSettingsUiEvent.ToggleVisibility(LibraryContentType.PLAYLISTS))
        advanceUntilIdle()

        coVerify(exactly = 1) {
            setLibraryDisplayPreferencesUseCase.invoke(
                match { preferences -> preferences.preferenceFor("PLAYLISTS").isVisible == false }
            )
        }
        val playlistsRow = viewModel.uiState.value.data?.rows?.first { it.section == LibraryContentType.PLAYLISTS }
        assertEquals(false, playlistsRow?.isVisible)
    }

    @Test
    fun `given only one section is visible when it is toggled then it stays visible and nothing is persisted`() = runTest {
        preferencesFlow.value = LibraryDisplayPreferences(
            sections = mapOf(
                "PLAYLISTS" to LibrarySectionDisplayPreference(isVisible = false),
                "ALBUMS" to LibrarySectionDisplayPreference(isVisible = false),
                "ARTISTS" to LibrarySectionDisplayPreference(isVisible = false),
                "GENRES" to LibrarySectionDisplayPreference(isVisible = false),
                "YEARS" to LibrarySectionDisplayPreference(isVisible = false),
                "COMPOSERS" to LibrarySectionDisplayPreference(isVisible = false),
            )
        )
        stubObservationUseCases()

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onEvent(LibrarySectionsSettingsUiEvent.ToggleVisibility(LibraryContentType.TRACKS))
        advanceUntilIdle()

        coVerify(exactly = 0) { setLibraryDisplayPreferencesUseCase.invoke(any()) }
        val tracksRow = viewModel.uiState.value.data?.rows?.first { it.section == LibraryContentType.TRACKS }
        assertEquals(true, tracksRow?.isVisible)
    }

    @Test
    fun `given a reorder move when handled then the new order is persisted`() = runTest {
        stubObservationUseCases()
        coEvery { setLibrarySectionOrderUseCase.invoke(any()) } returns Resource.Success(Unit)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onEvent(LibrarySectionsSettingsUiEvent.MoveSection(fromIndex = 0, toIndex = 2))
        advanceUntilIdle()

        coVerify(exactly = 1) {
            setLibrarySectionOrderUseCase.invoke(
                listOf("PLAYLISTS", "ALBUMS", "TRACKS", "ARTISTS", "GENRES", "YEARS", "COMPOSERS")
            )
        }
        assertEquals(
            listOf(
                LibraryContentType.PLAYLISTS,
                LibraryContentType.ALBUMS,
                LibraryContentType.TRACKS,
                LibraryContentType.ARTISTS,
                LibraryContentType.GENRES,
                LibraryContentType.YEARS,
                LibraryContentType.COMPOSERS,
            ),
            viewModel.uiState.value.data?.rows?.map { it.section }
        )
    }

    private fun stubObservationUseCases() {
        every { observeLibraryDisplayPreferencesUseCase.invoke() } returns preferencesFlow
        every { observeLibrarySectionOrderUseCase.invoke() } returns orderFlow
    }

    private fun createViewModel(): LibrarySectionsSettingsViewModel = LibrarySectionsSettingsViewModel(
        observeLibraryDisplayPreferencesUseCase = observeLibraryDisplayPreferencesUseCase,
        observeLibrarySectionOrderUseCase = observeLibrarySectionOrderUseCase,
        setLibraryDisplayPreferencesUseCase = setLibraryDisplayPreferencesUseCase,
        setLibrarySectionOrderUseCase = setLibrarySectionOrderUseCase,
        navigationManager = navigationManager,
        stringResolver = TestStringResolver,
        uiErrorMapper = TestUiErrorMapper,
    )
}
