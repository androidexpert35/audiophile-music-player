package com.androidexpert35.audiophilemusicplayer.data.scanner

import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the folder-scoped track query, which is what stops the library from indexing the
 * whole device (messenger voice notes included) instead of the folders the user granted.
 */
class MediaStoreColumnsTest {

    @Test
    fun `given one folder when selection is built then it constrains both volume and path`() {
        val selection = MediaStoreColumns.trackSelectionForFolders(listOf(musicFolder()))

        assertTrue(selection.startsWith(MediaStoreColumns.TRACK_SELECTION))
        // Each folder keeps its own parentheses so the outer group stays a valid OR list
        // once a second folder is granted.
        assertEquals(
            "duration > 30000 AND ((volume_name = ? AND relative_path LIKE ? ESCAPE '\\'))",
            selection
        )
    }

    @Test
    fun `given several folders when selection is built then each contributes its own clause`() {
        val selection = MediaStoreColumns.trackSelectionForFolders(
            listOf(musicFolder(), musicFolder(volumeName = "1a2b-3c4d", relativePath = "DSD/"))
        )

        assertEquals(2, Regex("volume_name = \\?").findAll(selection).count())
        assertTrue(selection.contains(") OR ("))
    }

    @Test
    fun `given a folder when arguments are built then the path becomes a prefix pattern`() {
        val args = MediaStoreColumns.trackSelectionArgsForFolders(listOf(musicFolder()))

        assertEquals(listOf("external_primary", "Music/DSD/%"), args.toList())
    }

    @Test
    fun `given a whole-volume grant when arguments are built then every path on it matches`() {
        val args = MediaStoreColumns.trackSelectionArgsForFolders(
            listOf(musicFolder(relativePath = ""))
        )

        assertEquals(listOf("external_primary", "%"), args.toList())
    }

    @Test
    fun `given wildcards in a folder name when arguments are built then they are escaped`() {
        // Without escaping, a folder literally named "Hi_Res 100%" would also match
        // "HiXRes ..." and every sibling path, silently widening the scan.
        val args = MediaStoreColumns.trackSelectionArgsForFolders(
            listOf(musicFolder(relativePath = "Hi_Res 100%/"))
        )

        assertEquals("Hi\\_Res 100\\%/%", args[1])
    }

    @Test
    fun `given a backslash in a folder name when arguments are built then it is escaped first`() {
        val args = MediaStoreColumns.trackSelectionArgsForFolders(
            listOf(musicFolder(relativePath = "Back\\slash/"))
        )

        assertEquals("Back\\\\slash/%", args[1])
    }

    @Test
    fun `given several folders when arguments are built then they follow the clause order`() {
        val args = MediaStoreColumns.trackSelectionArgsForFolders(
            listOf(
                musicFolder(),
                musicFolder(volumeName = "1a2b-3c4d", relativePath = "DSD/"),
            )
        )

        assertEquals(
            listOf("external_primary", "Music/DSD/%", "1a2b-3c4d", "DSD/%"),
            args.toList()
        )
    }

    private fun musicFolder(
        volumeName: String = "external_primary",
        relativePath: String = "Music/DSD/",
    ): MusicFolderScope = MusicFolderScope(
        // The URI only matters to the document walk, never to the MediaStore query.
        treeUri = mockk(relaxed = true),
        volumeName = volumeName,
        relativePath = relativePath,
        displayPath = relativePath.trimEnd('/'),
        storageLabel = "Internal storage",
    )
}
