package com.digifender.adminifender.core.data.platform

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.digifender.adminifender.core.data.extraction.ExtractionOutcome
import com.digifender.adminifender.core.data.extraction.ExtractionRunner
import java.util.concurrent.TimeUnit

interface ExtractionHost {
    val extraction: ExtractionRunner
}

/** Charging-only PDF and OCR work (lld.md#android); deferred attachments stay visibly queued until it runs. */
class ExtractionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val host = applicationContext as? ExtractionHost ?: return Result.failure()
        repeat(ATTACHMENTS_PER_RUN) {
            when (host.extraction.runOnce()) {
                is ExtractionOutcome.Published, is ExtractionOutcome.Failed -> Unit
                else -> return Result.success()
            }
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "device-extraction"
        private const val ATTACHMENTS_PER_RUN = 3

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresCharging(true)
                .setRequiresBatteryNotLow(true)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<ExtractionWorker>(30, TimeUnit.MINUTES).setConstraints(constraints).build())
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
