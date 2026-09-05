package com.androidexpert35.audiophilemusicplayer.data.repository

import com.androidexpert35.audiophilemusicplayer.domain.model.common.LibraryResourceError
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionDiagnosticsTest {
    @Test
    fun `session keeps codes and stack locations without private messages and bounds history`() {
        SessionDiagnostics.record(LibraryResourceError.SCAN_READ_FAILED)
        SessionDiagnostics.record(IllegalStateException("content://private/music/secret.flac"))
        val report = SessionDiagnostics.snapshot()
        assertTrue(report.contains("1005"))
        assertTrue(report.contains("IllegalStateException"))
        assertFalse(report.contains("secret.flac"))
        repeat(205) { SessionDiagnostics.record(LibraryResourceError.STORAGE_UNAVAILABLE) }
        val bounded = SessionDiagnostics.snapshot()
        assertTrue(bounded.contains("Older entries omitted:"))
        assertFalse(bounded.contains("IllegalStateException"))
    }
}
