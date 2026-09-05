package com.androidexpert35.audiophilemusicplayer.data.repository

import android.util.Log
import com.androidexpert35.audiophilemusicplayer.domain.model.common.LibraryResourceError

/** Writes support diagnostics without exception messages, content URIs or music metadata. */
internal object LibraryDiagnostics {
    const val TAG = "LibraryDiagnostics"

    fun record(error: LibraryResourceError, failure: Throwable? = null) {
        SessionDiagnostics.record(error)
        failure?.let(SessionDiagnostics::record)
        // Exception messages often contain the full document URI. Keep only the class
        // and code locations, which are sufficient to locate the failing operation.
        runCatching {
            Log.e(TAG, buildString {
                appendLine("Error: ${error.code}; reason=${error.name}")
                failure?.let {
                    appendLine(it.javaClass.name)
                    it.stackTrace.take(20).forEach { frame -> appendLine("at $frame") }
                }
            })
        }
    }
}
