package com.androidexpert35.audiophilemusicplayer.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Single-row Room entity describing whether the initial media index has already completed.
 *
 * Persisting this flag separately allows the onboarding flow to skip a redundant scan on
 * subsequent launches, including the edge case where the device contains zero audio files.
 *
 * @property id Fixed primary key for the single persisted row.
 * @property isCompleted Whether at least one full scan-and-index pass finished successfully.
 * @property indexedTrackCount Number of tracks persisted during the last successful index.
 * @property lastIndexedAtEpochMs UTC epoch timestamp in milliseconds for the last successful scan.
 * @property folderSignature Fingerprint of the music folders that produced this index. A
 *   catalogue whose signature no longer matches the granted folders is stale by definition —
 *   the user added or removed a folder — so it must be rebuilt rather than shown. Empty for
 *   indexes written before folder-scoped scanning existed, which forces exactly that rebuild.
 * @property artistNormalizationVersion Version of the artist-credit expansion rules used to
 *   build this index. Older versions are rebuilt so cached compound artist rows are retired.
 */
@Entity(tableName = "library_index_state")
data class LibraryIndexStateEntity(
    @PrimaryKey val id: Int = DEFAULT_ID,
    val isCompleted: Boolean,
    val indexedTrackCount: Int,
    val lastIndexedAtEpochMs: Long,
    val folderSignature: String = "",
    val artistNormalizationVersion: Int = CURRENT_ARTIST_NORMALIZATION_VERSION,
) {
    companion object {
        /** Fixed identifier for the singleton row storing index completion metadata. */
        const val DEFAULT_ID: Int = 1

        /** Current artist-credit expansion version written by successful index passes. */
        const val CURRENT_ARTIST_NORMALIZATION_VERSION: Int = 1
    }
}

