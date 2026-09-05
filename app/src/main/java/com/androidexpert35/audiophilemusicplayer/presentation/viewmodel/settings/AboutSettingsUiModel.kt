package com.androidexpert35.audiophilemusicplayer.presentation.viewmodel.settings

/** Keeps the report button disabled while the email attachment is prepared.
 * @property preparingReport Whether a report request is in progress.
 */
data class AboutSettingsUiModel(val preparingReport: Boolean = false)
