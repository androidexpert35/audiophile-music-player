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
 */
@Entity(tableName = "library_index_state")
data class LibraryIndexStateEntity(
    @PrimaryKey val id: Int = DEFAULT_ID,
    val isCompleted: Boolean,
    val indexedTrackCount: Int,
    val lastIndexedAtEpochMs: Long
) {
    companion object {
        /** Fixed identifier for the singleton row storing index completion metadata. */
        const val DEFAULT_ID: Int = 1
    }
}

