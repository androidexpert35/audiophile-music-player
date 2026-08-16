package com.androidexpert35.audiophilemusicplayer.data.repository

import android.content.SharedPreferences
import com.androidexpert35.audiophilemusicplayer.data.playback.engine.AudioEngineManager
import com.androidexpert35.audiophilemusicplayer.data.playback.usb.UsbDeviceScanner
import com.tony.coreui.domain.resource.Resource
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SettingsRepositoryImpl], focused on the library section order and
 * the section display preference decode compatibility added for the Library Sections
 * settings sub-screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsRepositoryImplTest {

    private val prefs = mockk<SharedPreferences>(relaxed = true)
    private val editor = mockk<SharedPreferences.Editor>(relaxed = true)
    private val audioEngineManager = mockk<AudioEngineManager>(relaxed = true)
    private val usbDeviceScanner = mockk<UsbDeviceScanner>(relaxed = true)

    private val repository = SettingsRepositoryImpl(
        prefs = prefs,
        audioEngineManager = audioEngineManager,
        usbDeviceScanner = usbDeviceScanner,
        ioDispatcher = Dispatchers.Unconfined,
    )

    @Test
    fun `given no stored order when observed then the default declaration order is emitted`() = runTest {
        every {
            prefs.getString(SettingsPreferences.KEY_LIBRARY_SECTION_ORDER, null)
        } returns null
        every { prefs.registerOnSharedPreferenceChangeListener(any()) } just Runs
        every { prefs.unregisterOnSharedPreferenceChangeListener(any()) } just Runs

        val order = repository.observeLibrarySectionOrder().first()

        assertEquals(SettingsPreferences.DEFAULT_LIBRARY_SECTION_ORDER, order)
    }

    @Test
    fun `given a stored comma delimited order when observed then it is split back into names`() = runTest {
        every {
            prefs.getString(SettingsPreferences.KEY_LIBRARY_SECTION_ORDER, null)
        } returns "ALBUMS,ARTISTS,TRACKS,PLAYLISTS"
        every { prefs.registerOnSharedPreferenceChangeListener(any()) } just Runs
        every { prefs.unregisterOnSharedPreferenceChangeListener(any()) } just Runs

        val order = repository.observeLibrarySectionOrder().first()

        assertEquals(listOf("ALBUMS", "ARTISTS", "TRACKS", "PLAYLISTS"), order)
    }

    @Test
    fun `given a new section order when persisted then it is committed as a comma delimited string`() = runTest {
        every { prefs.edit() } returns editor
        every {
            editor.putString(SettingsPreferences.KEY_LIBRARY_SECTION_ORDER, "ALBUMS,TRACKS")
        } returns editor
        every { editor.commit() } returns true

        val result = repository.setLibrarySectionOrder(listOf("ALBUMS", "TRACKS"))

        assertTrue(result is Resource.Success)
        verify(exactly = 1) {
            editor.putString(SettingsPreferences.KEY_LIBRARY_SECTION_ORDER, "ALBUMS,TRACKS")
        }
    }

    @Test
    fun `given commit fails when section order is persisted then a storage error is returned`() = runTest {
        every { prefs.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.commit() } returns false

        val result = repository.setLibrarySectionOrder(listOf("TRACKS"))

        assertTrue(result is Resource.Error)
    }

    @Test
    fun `given legacy 3-field and new 4-field entries when read then both decode with the correct visibility`() = runTest {
        every {
            prefs.getStringSet(SettingsPreferences.KEY_LIBRARY_DISPLAY_PREFERENCES, emptySet())
        } returns setOf(
            // Legacy format written before section visibility existed — defaults to visible.
            "TRACKS|RECENTLY_ADDED|false",
            // Current format with an explicit, hidden section.
            "PLAYLISTS|RECENTLY_ADDED|true|false",
        )

        val result = repository.getLibraryDisplayPreferences()

        assertTrue(result is Resource.Success)
        val sections = (result as Resource.Success).data.sections
        assertEquals(true, sections["TRACKS"]?.isVisible)
        assertEquals(false, sections["TRACKS"]?.isGridView)
        assertEquals(false, sections["PLAYLISTS"]?.isVisible)
        assertEquals(true, sections["PLAYLISTS"]?.isGridView)
    }

    @Test
    fun `given preferences with visibility when persisted then the encoded string carries the visibility field`() = runTest {
        every { prefs.edit() } returns editor
        every { editor.putStringSet(any(), any()) } returns editor
        every { editor.commit() } returns true

        val preferences = com.androidexpert35.audiophilemusicplayer.domain.model.library.LibraryDisplayPreferences(
            sections = mapOf(
                "ARTISTS" to com.androidexpert35.audiophilemusicplayer.domain.model.library.LibrarySectionDisplayPreference(
                    sortOrder = "RECENTLY_ADDED",
                    isGridView = false,
                    isVisible = false,
                )
            )
        )

        val result = repository.setLibraryDisplayPreferences(preferences)

        assertTrue(result is Resource.Success)
        verify(exactly = 1) {
            editor.putStringSet(
                SettingsPreferences.KEY_LIBRARY_DISPLAY_PREFERENCES,
                setOf("ARTISTS|RECENTLY_ADDED|false|false"),
            )
        }
    }
}
