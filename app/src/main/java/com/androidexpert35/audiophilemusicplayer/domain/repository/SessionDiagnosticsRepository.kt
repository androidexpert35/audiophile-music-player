package com.androidexpert35.audiophilemusicplayer.domain.repository

/** Keeps bounded, privacy-safe error diagnostics for the current app process. */
interface SessionDiagnosticsRepository {
    /** Records an error's type/code and stack locations, excluding its message and user data.
     * @param error Domain failure or exception being handled.
     */
    fun record(error: Any)

    /** Returns a consistent text snapshot for a user-initiated report.
     * @return Session start time and collected errors, including an explicit empty state.
     */
    fun snapshot(): String
}
