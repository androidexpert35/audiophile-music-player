package com.androidexpert35.audiophilemusicplayer.domain.repository

import com.androidexpert35.audiophilemusicplayer.domain.model.common.LibraryResourceError
import com.tony.coreui.domain.resource.Resource

/** Prepares support diagnostics and hands a draft to the user's email application. */
interface BugReportRepository {
    /**
     * Opens a prefilled report for the user to review and send.
     *
     * @param error Library failure being reported.
     * @return Success when the composer opens, never confirmation of email delivery.
     */
    suspend fun openEmail(error: LibraryResourceError): Resource<Unit>
}
