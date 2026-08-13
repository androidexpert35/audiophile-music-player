package com.androidexpert35.audiophilemusicplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.androidexpert35.audiophilemusicplayer.data.local.entity.ImportedPlaylistEntity
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO exposing reads and atomic replacement for `.m3u` playlists discovered inside
 * user-granted music folders.
 *
 * The full table is replaced on every library scan by
 * [com.androidexpert35.audiophilemusicplayer.data.repository.MediaIndexRepositoryImpl],
 * mirroring how [LibraryIndexDao.replaceIndexedLibrary] replaces the track catalogue.
 * Individual rows are also updated/removed directly by
 * [com.androidexpert35.audiophilemusicplayer.data.repository.PlaylistRepositoryImpl]
 * after an in-app edit or delete, so the change is visible before the next scan.
 */
@Dao
interface ImportedPlaylistDao {

    /**
     * Observes every discovered playlist, ordered by name.
     *
     * @return A stream that emits the full imported-playlist set whenever it changes.
     */
    @Query("SELECT * FROM imported_playlists ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<ImportedPlaylistEntity>>

    /**
     * Reads every discovered playlist once, for a synchronous name-collision check.
     *
     * @return All currently indexed discovered-playlist rows.
     */
    @Query("SELECT * FROM imported_playlists")
    suspend fun getAll(): List<ImportedPlaylistEntity>

    /**
     * Looks up one discovered playlist by its source document URI.
     *
     * @param documentUri Stable document URI of the source `.m3u` file.
     * @return The matching row, or `null` if no playlist with that URI is indexed.
     */
    @Query("SELECT * FROM imported_playlists WHERE documentUri = :documentUri LIMIT 1")
    suspend fun getByDocumentUri(documentUri: String): ImportedPlaylistEntity?

    /** Inserts or replaces one discovered-playlist row after an in-app edit. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(playlist: ImportedPlaylistEntity)

    /** Removes one discovered-playlist row after an in-app delete. */
    @Query("DELETE FROM imported_playlists WHERE documentUri = :documentUri")
    suspend fun deleteByDocumentUri(documentUri: String)

    /** Deletes every discovered-playlist row before a fresh scan replaces them. */
    @Query("DELETE FROM imported_playlists")
    suspend fun clear()

    /** Inserts or replaces the full discovered-playlist row set. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(playlists: List<ImportedPlaylistEntity>)

    /**
     * Atomically replaces the cached discovered-playlist table with a freshly scanned
     * snapshot.
     *
     * @param playlists Playlists found by the current scan pass.
     */
    @Transaction
    suspend fun replaceAll(playlists: List<ImportedPlaylistEntity>) {
        clear()
        insertAll(playlists)
    }
}
