package com.androidexpert35.audiophilemusicplayer.domain.model.common

import com.tony.coreui.domain.resource.ResourceError

/**
 * Converts a [ResourceError] into a single-line log-safe message.
 *
 * @return A concise string suitable for non-PII logging.
 */
fun ResourceError.toLogMessage(): String = when (this) {
    is ResourceError.LogicError -> "LogicError[code=$errorCode]: $errorMessage"
    is ResourceError.DatabaseError -> "DatabaseError: $message"
    is ResourceError.StorageError -> "StorageError: $message"
    is PlaybackResourceError -> "PlaybackError[code=$errorCode]: $message"
    is ResourceError.UnknownError -> "UnknownError"
    else -> "Unhandled error type: ${this::class.simpleName}"
}

/**
 * Converts a [ResourceError] into a user-facing message.
 *
 * @return A human-readable string safe for display in the UI.
 */
fun ResourceError.toUserMessage(): String = when (this) {
    is ResourceError.LogicError -> errorMessage ?: "An unexpected error occurred."
    is ResourceError.DatabaseError -> "Database error: $message"
    is ResourceError.StorageError -> "Could not access storage: $message"
    is PlaybackResourceError -> "Playback error: $message"
    is ResourceError.UnknownError -> "An unexpected error occurred."
    else -> "An unexpected error occurred."
}
