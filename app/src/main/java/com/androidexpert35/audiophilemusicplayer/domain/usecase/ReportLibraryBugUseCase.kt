package com.androidexpert35.audiophilemusicplayer.domain.usecase

import com.androidexpert35.audiophilemusicplayer.domain.model.common.LibraryResourceError
import com.androidexpert35.audiophilemusicplayer.domain.repository.BugReportRepository
import com.tony.coreui.domain.resource.Resource

/**
 * Lets the user report a library failure through a prefilled email.
 *
 * @property repository Prepares diagnostics and opens the email composer.
 */
class ReportLibraryBugUseCase(private val repository: BugReportRepository) {
    /**
     * @param error Library failure the user chose to report.
     * @return Success when the draft opens, or an actionable preparation/launch error.
     */
    suspend operator fun invoke(error: LibraryResourceError): Resource<Unit> = repository.openEmail(error)
}
