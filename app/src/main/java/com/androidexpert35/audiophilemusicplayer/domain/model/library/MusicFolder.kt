package com.androidexpert35.audiophilemusicplayer.domain.model.library

/**
 * A storage location the user has explicitly authorised the app to read music from.
 *
 * The library is built **only** from the folders in this collection. Restricting the
 * scan to user-chosen locations is what keeps unrelated audio (messenger voice notes,
 * app sound effects, podcasts caches) out of the catalogue, and it is also the only
 * way the app can read DSD files: `.dsf` / `.dff` are not media types Android grants
 * through `READ_MEDIA_AUDIO`, so they are reachable exclusively through the persisted
 * document-tree grant this model represents.
 *
 * @property id Opaque, stable identifier of the granted location. Treated as a plain
 *   string in Domain; the Data layer knows it is a persisted document-tree URI.
 * @property displayPath Human-readable path relative to its storage volume, e.g.
 *   `Music/DSD`. Blank when the user selected the whole volume.
 * @property storageLabel Name of the volume the folder lives on, e.g. `Internal storage`.
 */
data class MusicFolder(
    val id: String,
    val displayPath: String,
    val storageLabel: String,
)
