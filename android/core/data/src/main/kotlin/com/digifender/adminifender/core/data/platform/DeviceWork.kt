package com.digifender.adminifender.core.data.platform

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.digifender.adminifender.core.data.device.DeviceWorkRunner
import com.digifender.adminifender.core.data.device.RunOutcome
import com.digifender.adminifender.core.model.WorkKind
import java.util.concurrent.TimeUnit

/** Implemented by the Application so the background worker reaches the app's single runner. */
interface DeviceWorkHost {
    val deviceWork: DeviceWorkRunner
}

/**
 * Background local inference while charging. A run takes at most a few tasks; WorkManager timing is not an exact
 * service guarantee, so queued work stays visible on the server until a phone takes it (lld.md#text-extraction-and-ocr).
 */
class DeviceWorkWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val host = applicationContext as? DeviceWorkHost ?: return Result.failure()
        repeat(TASKS_PER_RUN) {
            when (host.deviceWork.runOnce(WorkKind.BACKGROUND)) {
                is RunOutcome.Submitted, RunOutcome.Declined, is RunOutcome.Refused -> Unit
                else -> return Result.success()
            }
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "device-work"
        private const val TASKS_PER_RUN = 3

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresCharging(true)
                .setRequiresBatteryNotLow(true)
                .build()
            val request = PeriodicWorkRequestBuilder<DeviceWorkWorker>(15, TimeUnit.MINUTES).setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
