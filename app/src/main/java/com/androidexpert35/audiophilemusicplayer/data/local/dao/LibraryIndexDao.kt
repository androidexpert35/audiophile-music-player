package com.androidexpert35.audiophilemusicplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.androidexpert35.audiophilemusicplayer.data.local.entity.AlbumEntity
import com.androidexpert35.audiophilemusicplayer.data.local.entity.ArtistEntity
import com.androidexpert35.audiophilemusicplayer.data.local.entity.LibraryIndexStateEntity
import com.androidexpert35.audiophilemusicplayer.data.local.entity.TrackEntity

/**
 * Room DAO exposing indexed-library reads and atomic cache replacement operations.
 *
 * The onboarding flow writes the entire library in one transaction, while the rest of the app
 * reads from these cached tables for fast catalogue access.
 */
@Dao
interface LibraryIndexDao {

    /**
     * Reads all indexed tracks in title order.
     *
     * @return Cached [TrackEntity] rows.
     */
    @Query("SELECT * FROM tracks ORDER BY title COLLATE NOCASE ASC")
    suspend fun getTracks(): List<TrackEntity>

    /**
     * Reads all indexed albums in title order.
     *
     * @return Cached [AlbumEntity] rows.
     */
    @Query("SELECT * FROM albums ORDER BY title COLLATE NOCASE ASC")
    suspend fun getAlbums(): List<AlbumEntity>

    /**
     * Reads all indexed artists in name order.
     *
     * @return Cached [ArtistEntity] rows.
     */
    @Query("SELECT * FROM artists ORDER BY name COLLATE NOCASE ASC")
    suspend fun getArtists(): List<ArtistEntity>

    /**
     * Fetches a batch of cached tracks by their MediaStore identifiers.
     *
     * Used during queue restoration to map persisted track IDs back into full domain
     * models without re-scanning MediaStore. Tracks absent from the index (e.g. deleted
     * files) are silently omitted from the result, so the caller must handle a shorter
     * list than [ids].
     *
     * @param ids List of MediaStore track identifiers to look up.
     * @return All matching [TrackEntity] rows found in the index (order not guaranteed).
     */
    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    suspend fun getTracksByIds(ids: List<Long>): List<TrackEntity>

    /**
     * Reads the stable content key stored against one indexed track.
     *
     * The measured-analysis cache is addressed by audio content rather than by
     * MediaStore id, so anything holding a track — the player telemetry read-out
     * above all — has to translate the id into the key before it can look a
     * measurement up. Projected to the single column because this runs on every
     * track change and the rest of the row is not wanted.
     *
     * @param trackId MediaStore identifier of the track to resolve.
     * @return The stored content key, empty for a track whose file could not be
     *   sampled at scan time, or `null` when the id is not in the index at all.
     */
    @Query("SELECT audioKey FROM tracks WHERE id = :trackId LIMIT 1")
    suspend fun getAudioKeyForTrack(trackId: Long): String?

    /**
     * Searches cached tracks by title, artist, or album using a case-insensitive match.
     *
     * @param query Free-text query entered by the user.
     * @return Matching [TrackEntity] rows.
     */
    @Query(
        """
        SELECT * FROM tracks
        WHERE title LIKE '%' || :query || '%' COLLATE NOCASE
            OR artistName LIKE '%' || :query || '%' COLLATE NOCASE
            OR albumTitle LIKE '%' || :query || '%' COLLATE NOCASE
        ORDER BY title COLLATE NOCASE ASC
        """
    )
    suspend fun searchTracks(query: String): List<TrackEntity>

    /**
     * Reads the persisted onboarding/index completion metadata.
     *
     * @return Singleton [LibraryIndexStateEntity] row, or `null` before the first completed index.
     */
    @Query("SELECT * FROM library_index_state WHERE id = :id LIMIT 1")
    suspend fun getLibraryIndexState(id: Int = LibraryIndexStateEntity.DEFAULT_ID): LibraryIndexStateEntity?

    /** Inserts or replaces the cached track rows. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTracks(tracks: List<TrackEntity>)

    /** Inserts or replaces the cached album rows. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAlbums(albums: List<AlbumEntity>)

    /** Inserts or replaces the cached artist rows. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertArtists(artists: List<ArtistEntity>)

    /** Inserts or replaces the singleton library-index completion row. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLibraryIndexState(state: LibraryIndexStateEntity)

    /** Deletes all cached tracks before replacing the library snapshot. */
    @Query("DELETE FROM tracks")
    suspend fun clearTracks()

    /** Deletes all cached albums before replacing the library snapshot. */
    @Query("DELETE FROM albums")
    suspend fun clearAlbums()

    /** Deletes all cached artists before replacing the library snapshot. */
    @Query("DELETE FROM artists")
    suspend fun clearArtists()

    // -------------------------------------------------------------------------
    // Remote image URL caching — Deezer enrichment
    // -------------------------------------------------------------------------

    /**
     * Looks up a single artist row by exact name match.
     *
     * Used by [com.androidexpert35.audiophilemusicplayer.data.repository.RemoteImageRepositoryImpl]
     * to check whether a Deezer image URL has already been cached before issuing
     * a network call.
     *
     * @param name Artist name matched case-insensitively against the indexed value.
     * @return The matching [ArtistEntity], or `null` if the artist is not indexed.
     */
    @Query("SELECT * FROM artists WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun getArtistByName(name: String): ArtistEntity?

    /**
     * Persists a resolved Deezer image URL for the given artist.
     *
     * Called after a successful remote lookup so subsequent requests are served
     * from the local cache without hitting the network.
     *
     * @param name Exact artist name used to target the update.
     * @param url Resolved Deezer image HTTPS URL to store.
     */
    @Query("UPDATE artists SET remoteImageUrl = :url WHERE name = :name COLLATE NOCASE")
    suspend fun updateArtistRemoteImageUrl(name: String, url: String)

    /**
     * Looks up a single album row by title and artist name.
     *
     * Used by [com.androidexpert35.audiophilemusicplayer.data.repository.RemoteImageRepositoryImpl]
     * to check whether a Deezer cover URL has already been cached for this album.
     *
     * @param title Exact album title.
     * @param artistName Exact album artist name.
     * @return The matching [AlbumEntity], or `null` if not found in the index.
     */
    @Query("SELECT * FROM albums WHERE title = :title AND artistName = :artistName LIMIT 1")
    suspend fun getAlbumByTitleAndArtist(title: String, artistName: String): AlbumEntity?

    /**
     * Persists a resolved Deezer cover URL for the given album.
     *
     * Called after a successful remote lookup so subsequent requests are served
     * from the local cache without hitting the network.
     *
     * @param title Exact album title used to target the update.
     * @param artistName Exact album artist name used to target the update.
     * @param url Resolved Deezer cover HTTPS URL to store.
     */
    @Query("UPDATE albums SET remoteArtUrl = :url WHERE title = :title AND artistName = :artistName")
    suspend fun updateAlbumRemoteArtUrl(title: String, artistName: String, url: String)

    /**
     * Atomically replaces the cached catalogue tables with a freshly scanned library snapshot.
     *
     * @param tracks Indexed track entities.
     * @param albums Indexed album entities.
     * @param artists Indexed artist entities.
     * @param state Completion metadata describing the successful index pass.
     */
    @Transaction
    suspend fun replaceIndexedLibrary(
        tracks: List<TrackEntity>,
        albums: List<AlbumEntity>,
        artists: List<ArtistEntity>,
        state: LibraryIndexStateEntity
    ) {
        // A MediaStore refresh replaces the structural catalogue, but remote
        // enrichment is independent of the scan and must survive it. Stable
        // MediaStore IDs are preferred; names/titles cover vendor-specific ID churn.
        val cachedArtists = getArtists()
        val cachedArtistsById = cachedArtists.associateBy(ArtistEntity::id)
        val cachedArtistsByName = cachedArtists.associateBy { artist -> artist.name.lowercase() }
        val enrichedArtists = artists.map { artist ->
            val cachedUrl = cachedArtistsById[artist.id]?.remoteImageUrl
                ?: cachedArtistsByName[artist.name.lowercase()]?.remoteImageUrl
            artist.copy(remoteImageUrl = cachedUrl)
        }

        val cachedAlbums = getAlbums()
        val cachedAlbumsById = cachedAlbums.associateBy(AlbumEntity::id)
        val cachedAlbumsByIdentity = cachedAlbums.associateBy { album ->
            album.title.lowercase() to album.artistName.lowercase()
        }
        val enrichedAlbums = albums.map { album ->
            val cachedUrl = cachedAlbumsById[album.id]?.remoteArtUrl
                ?: cachedAlbumsByIdentity[
                    album.title.lowercase() to album.artistName.lowercase()
                ]?.remoteArtUrl
            album.copy(remoteArtUrl = cachedUrl)
        }

        clearTracks()
        clearAlbums()
        clearArtists()
        insertTracks(tracks)
        insertAlbums(enrichedAlbums)
        insertArtists(enrichedArtists)
        upsertLibraryIndexState(state)
    }
}
