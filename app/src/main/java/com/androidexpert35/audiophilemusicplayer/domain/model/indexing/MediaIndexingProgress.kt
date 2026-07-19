package com.androidexpert35.audiophilemusicplayer.domain.model.indexing

/**
 * Domain progress snapshot emitted while the local media library is being indexed.
 *
 * @property progress Normalized completion value in the inclusive range `0f..1f`.
 * @property currentFile Display-friendly name or path fragment for the file currently being indexed.
 * @property indexedFiles Number of files processed so far.
 * @property totalFiles Total number of files discovered during the scan.
 */
data class MediaIndexingProgress(
    val progress: Float,
    val currentFile: String,
    val indexedFiles: Int,
    val totalFiles: Int
)

