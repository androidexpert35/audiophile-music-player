package com.androidexpert35.audiophilemusicplayer.data.repository

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.androidexpert35.audiophilemusicplayer.BuildConfig
import com.androidexpert35.audiophilemusicplayer.R
import com.androidexpert35.audiophilemusicplayer.di.IoDispatcher
import com.androidexpert35.audiophilemusicplayer.domain.model.common.LibraryResourceError
import com.androidexpert35.audiophilemusicplayer.domain.repository.BugReportRepository
import com.tony.coreui.domain.resource.Resource
import com.tony.coreui.domain.resource.ResourceError
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Attaches bounded app-only diagnostics to an email the user explicitly sends.
 *
 * @property context Application context for private cache files and the system chooser.
 * @property ioDispatcher Dispatcher for file writes and the bounded logcat subprocess.
 */
class BugReportRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : BugReportRepository {
    override suspend fun openEmail(error: LibraryResourceError): Resource<Unit> = withContext(ioDispatcher) {
        try {
            val emailPackages = context.packageManager.queryIntentActivities(
                Intent(Intent.ACTION_SENDTO, "mailto:".toUri()),
                PackageManager.MATCH_DEFAULT_ONLY,
            ).map { it.activityInfo.packageName }.distinct()
            if (emailPackages.isEmpty()) return@withContext unavailable(R.string.bug_report_no_email)

            val directory = File(context.cacheDir, "bug_reports").apply { mkdirs() }
            // Keep recent attachments intact while a draft may still be open in another app.
            directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > RETENTION_MS }
                ?.forEach { it.delete() }
            val report = File(directory, "audiophile-${UUID.randomUUID()}.txt")
            val details = buildString {
                appendLine("Audiophile library bug report")
                appendLine("Error: ${error.code} (${error.name})")
                appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("Android: ${Build.VERSION.RELEASE}; SDK: ${Build.VERSION.SDK_INT}")
                appendLine()
                appendLine("App library diagnostics (current process only):")
                append(captureLogcat())
            }
            report.writeText(details)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.bugreports", report)
            val candidates = emailPackages.map { packageName ->
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    setPackage(packageName)
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_EMAIL))
                    putExtra(Intent.EXTRA_SUBJECT, "Audiophile — Error: ${error.code}")
                    putExtra(Intent.EXTRA_TEXT, context.getString(R.string.bug_report_email_body, error.code))
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newRawUri("Diagnostics", uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }.filter { it.resolveActivity(context.packageManager) != null }
            if (candidates.isEmpty()) return@withContext unavailable(R.string.bug_report_no_email)
            val chooser = Intent.createChooser(candidates.first(), context.getString(R.string.bug_report_action))
                .putExtra(Intent.EXTRA_INITIAL_INTENTS, candidates.drop(1).toTypedArray())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            Resource.Success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            unavailable(R.string.bug_report_failed)
        }
    }

    private fun unavailable(messageId: Int): Resource.Error =
        Resource.Error(ResourceError.ServiceError(context.getString(messageId), null))

    private fun captureLogcat(): String {
        val output = File.createTempFile("library-logcat-", ".tmp", context.cacheDir)
        return try {
            val process = ProcessBuilder(
                "logcat", "-d", "--pid=${Process.myPid()}", "-t", "400", "-v", "threadtime",
                "${LibraryDiagnostics.TAG}:E", "*:S",
            ).redirectErrorStream(true).redirectOutput(output).start()
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS) || process.exitValue() != 0) {
                    "Logcat unavailable on this device. The error and device details above are still usable."
                } else {
                    output.readText().take(MAX_LOG_CHARS).ifBlank { "No recent library diagnostics available." }
                }
            } finally {
                process.destroyForcibly()
            }
        } catch (_: Exception) {
            "Logcat unavailable on this device. The error and device details above are still usable."
        } finally {
            output.delete()
        }
    }

    private companion object {
        const val SUPPORT_EMAIL = "developer@antoniocirielli.it"
        const val RETENTION_MS = 7 * 24 * 60 * 60 * 1000L
        const val MAX_LOG_CHARS = 128 * 1024
    }
}
