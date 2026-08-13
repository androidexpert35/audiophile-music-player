package com.androidexpert35.audiophilemusicplayer.data.mapper

import com.androidexpert35.audiophilemusicplayer.data.local.entity.AlbumEntity
import com.androidexpert35.audiophilemusicplayer.data.local.entity.ArtistEntity
import com.androidexpert35.audiophilemusicplayer.data.local.entity.TrackEntity
import com.androidexpert35.audiophilemusicplayer.data.scanner.ScannedAudioFile
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioCodec
import com.androidexpert35.audiophilemusicplayer.domain.model.audio.AudioFormat
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Album
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Artist
import com.androidexpert35.audiophilemusicplayer.domain.model.track.Track
import com.androidexpert35.audiophilemusicplayer.domain.model.track.extractNavigableArtistNames
import com.androidexpert35.audiophilemusicplayer.domain.model.track.isUnknownArtistName
import java.util.Locale

/**
 * Maps indexed-library storage models between MediaStore scan results, Room entities, and domain models.
 */
fun ScannedAudioFile.toTrackEntity(): TrackEntity {
    val codec = AudioCodec.fromMimeType(mimeType)
    return TrackEntity(
        id = id,
        title = title,
        artistId = artistId,
        artistName = artistName,
        albumId = albumId,
        albumTitle = albumTitle,
        durationMs = durationMs,
        contentUri = contentUri,
        filePath = filePath,
        trackNumber = trackNumber,
        discNumber = discNumber,
        mimeType = mimeType,
        fileSizeBytes = fileSizeBytes,
        dateAdded = dateAdded,
        sampleRateHz = sampleRateHz,
        bitDepth = bitDepth,
        channelCount = 2,
        isLossless = codec.isLossless,
        artUri = artUri,
    )
}

/**
 * Aggregates scanned tracks into album entities for fast cached library browsing.
 *
 * @receiver Full list of scanned audio files discovered during onboarding.
 * @return Derived [AlbumEntity] rows ordered by title.
 */
fun List<ScannedAudioFile>.toAlbumEntities(): List<AlbumEntity> =
    groupBy { it.albumId }
        .values
        .map { items ->
            val first = items.first()
            AlbumEntity(
                id = first.albumId,
                title = first.albumTitle,
                artistId = first.artistId,
                artistName = first.artistName,
                artUri = first.artUri,
                trackCount = items.size,
                year = items.firstOrNull { it.year > 0 }?.year ?: 0,
                totalDurationMs = items.sumOf { it.durationMs }
            )
        }
        .sortedBy { it.title.lowercase() }

/**
 * Aggregates scanned tracks into artist entities for fast cached library browsing.
 *
 * @receiver Full list of scanned audio files discovered during onboarding.
 * @return Derived [ArtistEntity] rows ordered by name.
 */
fun List<ScannedAudioFile>.toArtistEntities(): List<ArtistEntity> =
    flatMap { file ->
        extractNavigableArtistNames(file.artistName).map { artistName ->
            artistName to file
        }
    }
        .groupBy { (artistName, _) -> artistName.lowercase(Locale.ROOT) }
        .values
        .map { credits ->
            val artistName = credits.first().first
            val items = credits.map { (_, file) -> file }
            ArtistEntity(
                id = stableArtistId(artistName),
                name = artistName,
                albumCount = items.distinctBy { it.albumId }.size,
                trackCount = items.size,
                totalDurationMs = items.sumOf { it.durationMs }
            )
        }
        .sortedBy { it.name.lowercase(Locale.ROOT) }

/** Produces a deterministic 64-bit key for a normalized artist identity. */
private fun stableArtistId(artistName: String): Long {
    var hash = FNV_OFFSET_BASIS
    artistName.lowercase(Locale.ROOT).forEach { character ->
        hash = hash xor character.code.toLong()
        hash *= FNV_PRIME
    }
    return hash
}

private const val FNV_OFFSET_BASIS = -3750763034362895579L
private const val FNV_PRIME = 1099511628211L

/**
 * Maps a cached [TrackEntity] into the domain [Track] used by the rest of the app.
 *
 * @return Domain track snapshot.
 */
fun TrackEntity.toDomainTrack(): Track {
    val codec = AudioCodec.fromMimeType(mimeType)
    return Track(
        id = id,
        title = title,
        artistName = artistName,
        albumTitle = albumTitle,
        albumId = albumId,
        durationMs = durationMs,
        uri = contentUri,
        trackNumber = trackNumber,
        discNumber = discNumber,
        audioFormat = AudioFormat(
            sampleRateHz = sampleRateHz,
            bitDepth = bitDepth,
            channelCount = channelCount,
            codec = codec,
            isLossless = isLossless
        ),
        fileSizeBytes = fileSizeBytes,
        dateAdded = dateAdded,
        artUri = artUri,
    )
}

/**
 * Maps a cached [AlbumEntity] into the domain [Album] used by the library surfaces.
 *
 * The [AlbumEntity.remoteArtUrl] is surfaced as [Album.remoteArtUrl] so that the
 * presentation layer can offer it to Coil as a fallback when the local [Album.artUri]
 * yields no embedded artwork.
 *
 * @return Domain album snapshot.
 */
fun AlbumEntity.toDomainAlbum(): Album = Album(
    id = id,
    title = title,
    artistName = artistName,
    artUri = artUri,
    remoteArtUrl = remoteArtUrl,
    trackCount = trackCount,
    year = year
)

/**
 * Maps a cached [ArtistEntity] into the domain [Artist] used by the library surfaces.
 *
 * The [ArtistEntity.remoteImageUrl] is surfaced as [Artist.imageUrl] so that the
 * presentation layer can display a Deezer-sourced artist photo when no local artwork
 * is available.
 *
 * @return Domain artist snapshot.
 */
fun ArtistEntity.toDomainArtist(): Artist = Artist(
    id = id,
    name = name,
    albumCount = albumCount,
    trackCount = trackCount,
    imageUrl = remoteImageUrl.takeUnless { name.isUnknownArtistName() }
)
