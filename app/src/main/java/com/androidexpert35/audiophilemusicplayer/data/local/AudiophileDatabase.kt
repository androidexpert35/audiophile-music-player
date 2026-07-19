package com.androidexpert35.audiophilemusicplayer.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.androidexpert35.audiophilemusicplayer.data.local.AudiophileDatabase.Companion.MIGRATION_1_2
import com.androidexpert35.audiophilemusicplayer.data.local.AudiophileDatabase.Companion.MIGRATION_2_3
import com.androidexpert35.audiophilemusicplayer.data.local.AudiophileDatabase.Companion.MIGRATION_3_4
import com.androidexpert35.audiophilemusicplayer.data.local.AudiophileDatabase.Companion.MIGRATION_4_5
import com.androidexpert35.audiophilemusicplayer.data.local.AudiophileDatabase.Companion.MIGRATION_5_6
import com.androidexpert35.audiophilemusicplayer.data.local.AudiophileDatabase.Companion.MIGRATION_6_7
import com.androidexpert35.audiophilemusicplayer.data.local.AudiophileDatabase.Companion.MIGRATION_7_8
import com.androidexpert35.audiophilemusicplayer.data.local.converter.LongListTypeConverter
import com.androidexpert35.audiophilemusicplayer.data.local.dao.LibraryIndexDao
import com.androidexpert35.audiophilemusicplayer.data.local.dao.LikedSongDao
import com.androidexpert35.audiophilemusicplayer.data.local.dao.LyricsCacheDao
import com.androidexpert35.audiophilemusicplayer.data.local.dao.PlaybackStateDao
import com.androidexpert35.audiophilemusicplayer.data.local.dao.RecentlyPlayedDao
import com.androidexpert35.audiophilemusicplayer.data.local.entity.AlbumEntity
import com.androidexpert35.audiophilemusicplayer.data.local.entity.ArtistEntity
import com.androidexpert35.audiophilemusicplayer.data.local.entity.LibraryIndexStateEntity
import com.androidexpert35.audiophilemusicplayer.data.local.entity.LikedSongEntity
import com.androidexpert35.audiophilemusicplayer.data.local.entity.LyricsCacheEntity
import com.androidexpert35.audiophilemusicplayer.data.local.entity.PlaybackStateEntity
import com.androidexpert35.audiophilemusicplayer.data.local.entity.RecentlyPlayedEntity
import com.androidexpert35.audiophilemusicplayer.data.local.entity.TrackEntity

/**
 * Room database storing the indexed local music catalogue, playback session state,
 * the user's liked-songs collection, the recently-played history, and the lyrics
 * cache.
 *
 * **Schema history**
 * - Version 1: initial library index tables.
 * - Version 2: added `playback_state` table via [MIGRATION_1_2].
 * - Version 3: added `remoteImageUrl` / `remoteArtUrl` columns via [MIGRATION_2_3].
 * - Version 4: added `liked_songs` and `recently_played` tables via [MIGRATION_3_4].
 * - Version 5: added `artUri` column to `tracks` via [MIGRATION_4_5].
 * - Version 6: added `lyrics_cache` table via [MIGRATION_5_6].
 * - Version 7: invalidated artist URLs resolved by the former first-result
 *   Deezer matcher via [MIGRATION_6_7].
 * - Version 8: added persistent per-track play counts via [MIGRATION_7_8].
 */
@Database(
    entities = [
        TrackEntity::class,
        AlbumEntity::class,
        ArtistEntity::class,
        LibraryIndexStateEntity::class,
        PlaybackStateEntity::class,
        LikedSongEntity::class,
        RecentlyPlayedEntity::class,
        LyricsCacheEntity::class,
    ],
    version = 8,
    exportSchema = false
)
@TypeConverters(LongListTypeConverter::class)
abstract class AudiophileDatabase : RoomDatabase() {

    /** @return DAO for indexed-library reads and cache replacement writes. */
    abstract fun libraryIndexDao(): LibraryIndexDao

    /** @return DAO for reading and writing the singleton playback-session row. */
    abstract fun playbackStateDao(): PlaybackStateDao

    /** @return DAO for reading and writing the user's liked-songs collection. */
    abstract fun likedSongDao(): LikedSongDao

    /** @return DAO for reading and writing the recently-played history. */
    abstract fun recentlyPlayedDao(): RecentlyPlayedDao

    /** @return DAO for reading and writing the lyrics cache. */
    abstract fun lyricsCacheDao(): LyricsCacheDao

    companion object {
        /** File name used for the Room database on disk. */
        const val DATABASE_NAME: String = "audiophile_music.db"

        /**
         * Migration from schema version 1 to 2.
         *
         * Creates the `playback_state` table for cross-launch session persistence.
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `playback_state` (
                        `id`                  INTEGER NOT NULL,
                        `currentTrackId`      INTEGER NOT NULL,
                        `playbackPositionMs`  INTEGER NOT NULL,
                        `currentQueueIndex`   INTEGER NOT NULL,
                        `queueTrackIds`       TEXT    NOT NULL,
                        `repeatMode`          TEXT    NOT NULL,
                        `shuffleMode`         TEXT    NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * Migration from schema version 2 to 3.
         *
         * Adds nullable Deezer remote-image URL columns to `artists` and `albums`.
         */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE artists ADD COLUMN remoteImageUrl TEXT")
                db.execSQL("ALTER TABLE albums ADD COLUMN remoteArtUrl TEXT")
            }
        }

        /**
         * Migration from schema version 3 to 4.
         *
         * Adds two new tables:
         * - `liked_songs` — persists the user's liked track IDs with a timestamp.
         * - `recently_played` — persists the most-recent play event per track,
         *   ordered by [playedAt] descending for recency queries.
         *
         * All existing rows in other tables are untouched; the user's cached
         * music catalogue and playback session are fully preserved.
         */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `liked_songs` (
                        `trackId` INTEGER NOT NULL,
                        `likedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`trackId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `recently_played` (
                        `trackId` INTEGER NOT NULL,
                        `playedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`trackId`)
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * Migration from schema version 4 to 5.
         *
         * Adds a nullable `artUri` column to the `tracks` table.
         *
         * For regular MediaStore-indexed tracks the value will be `NULL` after
         * migration (existing rows are unaffected). New DSD track rows inserted
         * after re-indexing will carry a `file://` URI pointing to the APIC picture
         * cached in the app's `cacheDir` by
         * [com.androidexpert35.audiophilemusicplayer.data.scanner.DsdFileScanner].
         * Regular tracks will have the `content://media/external/audio/albumart/<albumId>`
         * URI stored here after the next full index pass.
         */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tracks ADD COLUMN artUri TEXT")
            }
        }

        /**
         * Migration from schema version 5 to 6.
         *
         * Adds the `lyrics_cache` table for caching LRCLIB API responses locally.
         *
         * `notFound` is stored as an INTEGER boolean sentinel so the repository
         * can avoid repeated network calls for tracks with no lyrics.
         */
        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `lyrics_cache` (
                        `cacheKey`        TEXT    NOT NULL,
                        `syncedLyricsRaw` TEXT,
                        `plainLyrics`     TEXT,
                        `isInstrumental`  INTEGER NOT NULL,
                        `notFound`        INTEGER NOT NULL,
                        `fetchedAtMs`     INTEGER NOT NULL,
                        PRIMARY KEY(`cacheKey`)
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * Migration from schema version 6 to 7.
         *
         * Invalidates artist image URLs selected by the legacy Deezer integration,
         * which trusted the first search result without verifying artist identity.
         * Album artwork and all local catalogue data remain untouched. Artist images
         * are lazily repopulated by the strict exact-name matcher as rows become visible.
         */
        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE artists SET remoteImageUrl = NULL")
            }
        }

        /**
         * Migration from schema version 7 to 8.
         *
         * Adds an aggregate playback-start counter to each existing recent-history
         * row. Historical rows are seeded with one known play because the former
         * schema retained recency but did not retain enough information to reconstruct
         * earlier counts. All future playback starts increment the value atomically.
         */
        val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE recently_played ADD COLUMN playCount INTEGER NOT NULL DEFAULT 1"
                )
            }
        }
    }
}
