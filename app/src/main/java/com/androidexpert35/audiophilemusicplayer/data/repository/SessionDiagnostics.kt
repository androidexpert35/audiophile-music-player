package com.androidexpert35.audiophilemusicplayer.data.repository

import com.androidexpert35.audiophilemusicplayer.domain.model.common.LibraryResourceError
import com.androidexpert35.audiophilemusicplayer.domain.model.common.PlaybackResourceError
import com.androidexpert35.audiophilemusicplayer.domain.repository.SessionDiagnosticsRepository
import java.time.Instant

/** Holds the latest 200 handled errors in memory until the app process ends. */
internal object SessionDiagnostics : SessionDiagnosticsRepository {
    private val startedAt = Instant.now()
    private val entries = ArrayDeque<String>()
    private var omitted = 0

    @Synchronized
    override fun record(error: Any) {
        val description = when (error) {
            is LibraryResourceError -> "Library error ${error.code}: ${error.name}"
            is PlaybackResourceError -> "Playback error: ${error.errorCode}"
            is Throwable -> buildString {
                appendLine(error.javaClass.name)
                error.stackTrace.take(20).forEach { appendLine("at $it") }
            }
            else -> error.javaClass.simpleName
        }
        if (entries.size == MAX_ENTRIES) {
            entries.removeFirst()
            omitted++
        }
        entries.addLast("${Instant.now()} $description")
    }

    @Synchronized
    override fun snapshot(): String = buildString {
        appendLine("Current process session started: $startedAt")
        if (omitted > 0) appendLine("Older entries omitted: $omitted")
        if (entries.isEmpty()) appendLine("No handled errors recorded in this session.")
        entries.forEach { appendLine(it) }
    }

    private const val MAX_ENTRIES = 200
}
