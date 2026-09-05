package com.androidexpert35.audiophilemusicplayer.domain.model.common

import com.tony.coreui.domain.resource.ResourceError

/**
 * Identifies library failures without exposing file paths or provider exception messages.
 *
 * Codes are stable support identifiers and must never be reassigned to another failure.
 * Recoverability means repeating the same operation can help; selecting a different
 * location or granting permission is a separate user action.
 *
 * @property code Stable numeric identifier shown in the error dialog and bug report.
 * @property isRecoverable Whether retrying the failed operation can reasonably help.
 */
enum class LibraryResourceError(
    val code: Int,
    val isRecoverable: Boolean,
) : ResourceError {
    /** The chosen location cannot be addressed by the local library scanner. */
    UNSUPPORTED_FOLDER(1001, false),
    /** The system refused durable access to the selected folder. */
    FOLDER_PERMISSION_DENIED(1002, false),
    /** The app could not persist the selected folder in its settings. */
    FOLDER_SAVE_FAILED(1003, true),
    /** Previously selected storage is temporarily unavailable. */
    STORAGE_UNAVAILABLE(1004, true),
    /** Reading the selected library failed during an I/O operation. */
    SCAN_READ_FAILED(1005, true),
    /** Reading the library requires the user to restore access. */
    SCAN_PERMISSION_DENIED(1006, false),
    /** An unclassified indexing failure needs investigation before recommending retry. */
    SCAN_FAILED(1007, false),
    /** An unclassified folder-selection failure needs investigation. */
    FOLDER_FAILED(1008, false),
}
