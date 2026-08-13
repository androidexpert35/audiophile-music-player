package com.androidexpert35.audiophilemusicplayer.data.repository

import android.content.ContentResolver
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import com.androidexpert35.audiophilemusicplayer.data.scanner.MusicFolderScopeResolver
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies one-time folder-selection upgrades enforced by the scan-scope registry. */
@OptIn(ExperimentalCoroutinesApi::class)
class MusicFolderRegistryTest {

    @Test
    fun `given legacy folder selection when folders checked then grant is retired and onboarding is required`() =
        runTest {
            val prefs = mockk<SharedPreferences>()
            val editor = mockk<SharedPreferences.Editor>()
            val contentResolver = mockk<ContentResolver>()
            val legacyUri = mockk<Uri>()
            val legacyUriString = "content://legacy/music"

            every {
                prefs.getInt(SettingsPreferences.KEY_MUSIC_FOLDER_SELECTION_VERSION, 0)
            } returns 0
            every {
                prefs.getStringSet(SettingsPreferences.KEY_MUSIC_FOLDER_URIS, emptySet())
            } returnsMany listOf(setOf(legacyUriString), emptySet())
            every { prefs.edit() } returns editor
            every { editor.remove(SettingsPreferences.KEY_MUSIC_FOLDER_URIS) } returns editor
            every {
                editor.putInt(
                    SettingsPreferences.KEY_MUSIC_FOLDER_SELECTION_VERSION,
                    SettingsPreferences.CURRENT_MUSIC_FOLDER_SELECTION_VERSION,
                )
            } returns editor
            every { editor.commit() } returns true
            every {
                contentResolver.releasePersistableUriPermission(
                    legacyUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } just Runs

            mockkStatic(Uri::class)
            every { Uri.parse(legacyUriString) } returns legacyUri
            try {
                val registry = MusicFolderRegistry(
                    prefs = prefs,
                    contentResolver = contentResolver,
                    scopeResolver = mockk<MusicFolderScopeResolver>(),
                    ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                )

                assertFalse(registry.hasStoredFolders())

                verify(exactly = 1) {
                    contentResolver.releasePersistableUriPermission(
                        legacyUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                verify(exactly = 1) { editor.commit() }
            } finally {
                unmockkStatic(Uri::class)
            }
        }

    @Test
    fun `given current folder selection when folders checked then stored selection is preserved`() = runTest {
        val prefs = mockk<SharedPreferences>()
        every {
            prefs.getInt(SettingsPreferences.KEY_MUSIC_FOLDER_SELECTION_VERSION, 0)
        } returns SettingsPreferences.CURRENT_MUSIC_FOLDER_SELECTION_VERSION
        every {
            prefs.getStringSet(SettingsPreferences.KEY_MUSIC_FOLDER_URIS, emptySet())
        } returns setOf("content://current/music")

        val registry = MusicFolderRegistry(
            prefs = prefs,
            contentResolver = mockk<ContentResolver>(),
            scopeResolver = mockk<MusicFolderScopeResolver>(),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        assertTrue(registry.hasStoredFolders())

        verify(exactly = 0) { prefs.edit() }
    }
}
