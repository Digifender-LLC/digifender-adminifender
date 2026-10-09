package com.digifender.adminifender.core.data.platform

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.digifender.adminifender.core.data.export.ExportRepository
import com.digifender.adminifender.core.data.export.ExportScheduler
import com.digifender.adminifender.core.data.files.AttachmentRepository
import com.digifender.adminifender.core.data.files.DownloadResult
import com.digifender.adminifender.core.data.files.DownloadScheduler
import com.digifender.adminifender.core.data.network.isUuid
import java.util.concurrent.TimeUnit

/** Implemented by the Application so the default WorkManager factory can reach the app's one attachment and export repositories. */
interface FilesHost {
    val attachments: AttachmentRepository
    val exports: ExportRepository
}

private const val KEY_ID = "id"

/**
 * One run of one attachment's download. Completion says only that this run ended; the Room row says whether the file
 * is ready. A run that finds its request purged, restarted or restricted ends quietly.
 */
class AttachmentWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val host = applicationContext as? FilesHost ?: return Result.failure()
        val id = inputData.getString(KEY_ID)?.takeIf(::isUuid) ?: return Result.failure()
        return when (host.attachments.run(id)) {
            // The row went back to queued with its attempt counted; WorkManager waits out the backoff, and the cap is in the repository.
            is DownloadResult.Retry -> Result.retry()
            DownloadResult.Done, DownloadResult.Superseded, DownloadResult.SignIn, is DownloadResult.Failed -> Result.success()
        }
    }
}

/** Unique work per attachment: asking twice is one download, and removing the download cancels exactly that work. */
class WorkDownloadScheduler(context: Context) : DownloadScheduler {
    private val work = WorkManager.getInstance(context)
    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    private fun name(id: String) = "attachment-$id"

    override fun enqueue(attachmentId: String) {
        val request = OneTimeWorkRequestBuilder<AttachmentWorker>()
            .setConstraints(online)
            .setInputData(workDataOf(KEY_ID to attachmentId))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        work.enqueueUniqueWork(name(attachmentId), ExistingWorkPolicy.KEEP, request)
    }

    override fun cancel(attachmentId: String) {
        work.cancelUniqueWork(name(attachmentId))
    }

    override fun cancelAll() {
        work.cancelAllWorkByTag(TAG)
    }

    private companion object {
        const val TAG = "attachment-download"
        const val BACKOFF_SECONDS = 30L
    }
}

/** Reads an export's state once and stores it. Stops being rescheduled when the export settles or after [MAX_RUNS] reads. */
class ExportPollWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val host = applicationContext as? FilesHost ?: return Result.failure()
        val id = inputData.getString(KEY_ID)?.takeIf(::isUuid) ?: return Result.failure()
        val settled = host.exports.refreshForWorker(id)
        return if (settled || runAttemptCount >= MAX_RUNS) Result.success() else Result.retry()
    }

    private companion object {
        const val MAX_RUNS = 12
    }
}

class WorkExportScheduler(context: Context) : ExportScheduler {
    private val work = WorkManager.getInstance(context)
    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    override fun schedulePoll(localId: String) {
        val request = OneTimeWorkRequestBuilder<ExportPollWorker>()
            .setConstraints(online)
            .setInputData(workDataOf(KEY_ID to localId))
            .setInitialDelay(INITIAL_DELAY_SECONDS, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        work.enqueueUniqueWork("export-poll-$localId", ExistingWorkPolicy.KEEP, request)
    }

    override fun cancelAll() {
        work.cancelAllWorkByTag(TAG)
    }

    private companion object {
        const val TAG = "export-poll"
        const val INITIAL_DELAY_SECONDS = 30L
        const val BACKOFF_SECONDS = 60L
    }
}
