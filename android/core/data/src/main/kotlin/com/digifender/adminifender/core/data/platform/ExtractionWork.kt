package com.digifender.adminifender.core.data.platform

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.digifender.adminifender.core.data.extraction.DeviceExtractor
import com.digifender.adminifender.core.data.extraction.ExtractionScheduler
import com.digifender.adminifender.core.data.extraction.RunConditions
import com.digifender.adminifender.core.data.extraction.RunResult
import com.digifender.adminifender.core.data.network.ExtractionOutcome
import java.util.concurrent.TimeUnit

/** Implemented by the Application so the default WorkManager factory can reach the app's one [DeviceExtractor]. */
interface ExtractionHost {
    val extractor: DeviceExtractor
}

/** Files handled in one run of the worker; a long backlog continues in the next run. */
private const val MAX_FILES_PER_RUN = 10

/**
 * Reads deferred PDFs and images one after another until there is nothing left, a limit stops the run, or the Worker cannot
 * be reached. Completion says only that this run ended; the attachment's state, synced from the Worker, says what happened.
 */
class ExtractionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val host = applicationContext as? ExtractionHost ?: return Result.failure()
        repeat(MAX_FILES_PER_RUN) {
            when (val result = host.extractor.runOnce()) {
                is RunResult.Published, RunResult.Deleted, is RunResult.LeaseEnded -> Unit
                // A file given back as unavailable would only be claimed again at once: leave it to the backoff.
                is RunResult.GaveBack -> if (result.outcome == ExtractionOutcome.UNAVAILABLE) return Result.retry()
                is RunResult.Idle, RunResult.SignIn -> return Result.success()
                is RunResult.Stopped, is RunResult.Retry -> return Result.retry()
            }
        }
        return Result.retry()
    }
}

class WorkExtractionScheduler(context: Context) : ExtractionScheduler {
    private val work = WorkManager.getInstance(context)

    // Reading a document is worth doing only with the Worker reachable (the text must be sent) and the battery not low.
    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresBatteryNotLow(true).build()

    override fun requestNow() {
        val request = OneTimeWorkRequestBuilder<ExtractionWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .addTag(TAG)
            .build()
        work.enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
    }

    override fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<ExtractionWorker>(PERIOD_HOURS, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .addTag(TAG)
            .build()
        work.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    override fun cancel() {
        work.cancelAllWorkByTag(TAG)
    }

    private companion object {
        const val TAG = "device-extraction"
        const val NOW = "device-extraction-now"
        const val PERIODIC = "device-extraction-periodic"
        const val PERIOD_HOURS = 6L
        const val BACKOFF_MINUTES = 5L
    }
}

/** Stops a run between pages when the phone is hot or nearly empty and not charging. */
class AndroidRunConditions(context: Context) : RunConditions {
    private val power = context.getSystemService(PowerManager::class.java)
    private val battery = context.getSystemService(BatteryManager::class.java)

    override fun stopReason(): String? {
        if (power.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) return "thermal"
        val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (!battery.isCharging && level in 0 until LOW_BATTERY_PERCENT) return "battery_low"
        return null
    }

    private companion object {
        const val LOW_BATTERY_PERCENT = 20
    }
}
