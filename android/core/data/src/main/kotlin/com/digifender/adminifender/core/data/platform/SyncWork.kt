package com.digifender.adminifender.core.data.platform

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.digifender.adminifender.core.data.repository.SyncScheduler
import com.digifender.adminifender.core.data.sync.SyncEngine
import com.digifender.adminifender.core.data.sync.SyncOutcome
import java.util.concurrent.TimeUnit

/** Implemented by the Application so the default WorkManager factory can reach the app's single sync engine. */
interface SyncHost {
    val syncEngine: SyncEngine
}

/** One sync pass. Completion means the cache caught up; it never stands for an external operation completing. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val host = applicationContext as? SyncHost ?: return Result.failure()
        return when (host.syncEngine.syncOnce()) {
            is SyncOutcome.Synced -> Result.success()
            // Nothing to retry until the owner signs in; queued edits stay held.
            SyncOutcome.SignInRequired -> Result.success()
            is SyncOutcome.Unavailable -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 5
    }
}

/** Unique work names so repeated requests collapse into one pass; sign-out cancels both. */
class WorkSyncScheduler(context: Context) : SyncScheduler {
    private val work = WorkManager.getInstance(context)
    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    override fun requestNow() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(online)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        // APPEND_OR_REPLACE: an edit made while a pass runs still gets its own pass afterwards.
        work.enqueueUniqueWork(NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    override fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(PERIOD_MINUTES, TimeUnit.MINUTES).setConstraints(online).build()
        work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun cancelAll() {
        work.cancelUniqueWork(NOW)
        work.cancelUniqueWork(PERIODIC)
    }

    private companion object {
        const val NOW = "sync-now"
        const val PERIODIC = "sync-periodic"
        const val PERIOD_MINUTES = 15L
        const val BACKOFF_SECONDS = 30L
    }
}
