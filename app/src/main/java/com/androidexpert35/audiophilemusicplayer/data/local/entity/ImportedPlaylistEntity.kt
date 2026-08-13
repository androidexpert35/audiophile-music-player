package com.androidexpert35.audiophilemusicplayer.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity representing a `.m3u`/`.m3u8` playlist discovered inside a user-granted
 * music folder during a library scan.
 *
 * Unlike [com.androidexpert35.audiophilemusicplayer.data.repository.PlaylistRepositoryImpl]'s
 * app-private playlists, these rows mirror a file the user owns on disk. The row is
 * replaced wholesale on every scan by
 * [com.androidexpert35.audiophilemusicplayer.data.scanner.M3uFileScanner], and edited
 * in place through its [documentUri] rather than through an app-private copy.
 *
 * @property documentUri Stable `DocumentsContract` document URI of the source `.m3u` file,
 *   reused as the playlist's domain ID so mutations can write straight back to the file.
 * @property name Playlist title, from the extended-M3U `#PLAYLIST:` header or the file name.
 * @property trackUris Audio content URIs resolved from the file's entries, in file order.
 * @property lastModifiedMs Source document's last-modified time at scan time.
 */
@Entity(tableName = "imported_playlists")
data class ImportedPlaylistEntity(
    @PrimaryKey val documentUri: String,
    val name: String,
    val trackUris: List<String>,
    val lastModifiedMs: Long,
)
