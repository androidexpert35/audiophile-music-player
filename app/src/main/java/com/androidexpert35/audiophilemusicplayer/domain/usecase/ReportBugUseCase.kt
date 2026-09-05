package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.model.common.LibraryResourceError
import com.androidexpert35.audiophilemusicplayer.domain.repository.BugReportRepository
import com.tony.coreui.domain.resource.Resource

/**
 * Opens a diagnostic email from settings or an error recovery screen.
 *
 * @property repository Prepares session diagnostics and opens the email composer.
 */
class ReportBugUseCase(private val repository: BugReportRepository) {
    /**
     * Prepares the same session report with optional context about a selected failure.
     *
     * @param error Failure selected in an error dialog, or null for a general report.
     * @return Success when the composer opens, never confirmation of email delivery.
     */
    suspend operator fun invoke(error: LibraryResourceError? = null): Resource<Unit> =
        repository.openEmail(error)
}
