package com.androidexpert35.audiophilemusicplayer.presentation.screen.onboarding.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.androidexpert35.audiophilemusicplayer.R
import com.tony.coreui.presentation.state.UIError

/**
 * Shows a support code and a plain-language recovery message, keeping diagnostics in email.
 *
 * @param error Mapped error, with a retry callback only for recoverable failures.
 * @param preparingReport Whether the diagnostic attachment is being prepared.
 * @param reportFailure Explanation if preparing or opening the email failed.
 * @param onReportBug User intent to open a prefilled email draft.
 * @param onDismiss Dismisses the dialog while keeping the recovery screen accessible.
 */
@Composable
fun LibraryErrorDialog(
    error: UIError,
    preparingReport: Boolean,
    reportFailure: String?,
    onReportBug: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!preparingReport) onDismiss() },
        title = { Text(error.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(error.message)
                if (preparingReport) Text(stringResource(R.string.bug_report_preparing))
                reportFailure?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            error.retryAction?.let { retry ->
                TextButton(onClick = retry, enabled = !preparingReport) {
                    Text(stringResource(R.string.error_retry_action))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onReportBug, enabled = !preparingReport) {
                Text(stringResource(R.string.bug_report_action))
            }
        }
    )
}
