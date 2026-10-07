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
import com.digifender.adminifender.core.data.devicework.DeviceWorkEngine
import com.digifender.adminifender.core.data.devicework.DeviceWorkState
import java.util.concurrent.TimeUnit

interface DeviceWorkHost { val deviceWorkEngine: DeviceWorkEngine }

class DeviceWorkWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val host = applicationContext as? DeviceWorkHost ?: return Result.failure()
        return when (host.deviceWorkEngine.runOnce()) {
            is DeviceWorkState.Unavailable -> if (runAttemptCount < 3) Result.retry() else Result.success()
            else -> Result.success()
        }
    }
}

/** Trigger only from ordinary app/sync activity, never an exact-time claim or a model-saving wakeup. */
class WorkDeviceScheduler(context: Context) {
    private val work = WorkManager.getInstance(context)

    fun request() {
        val request = OneTimeWorkRequestBuilder<DeviceWorkWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).setRequiresCharging(true).setRequiresBatteryNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        work.enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
    }

    fun cancel() { work.cancelUniqueWork(NAME) }

    private companion object { const val NAME = "device-work" }
}
