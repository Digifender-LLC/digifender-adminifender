package com.digifender.adminifender.push

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.digifender.adminifender.AdminifenderApplication
import com.digifender.adminifender.BuildConfig
import com.digifender.adminifender.core.data.push.PushPermission
import com.digifender.adminifender.core.data.push.PushRegisterResult
import com.digifender.adminifender.core.data.push.registrationDigest
import com.digifender.adminifender.core.data.session.SessionState
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Registers this device's FCM token and notification permission with the Worker. Runs after sign-in, on token
 * rotation and when the permission changes; a digest of the last success avoids repeat calls.
 */
class PushRegistrationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as AdminifenderApplication).container
        val deviceId = (container.session.restore() as? SessionState.SignedIn)?.deviceId ?: return Result.success()
        if (FirebaseApp.getApps(applicationContext).isEmpty()) return Result.success()
        val token = fcmToken() ?: return Result.retry()
        val permission = if (NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()) PushPermission.GRANTED else PushPermission.DENIED
        val digest = registrationDigest(deviceId, token, permission, BuildConfig.VERSION_NAME)
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(LAST_DIGEST, null) == digest) return Result.success()
        // The key stays fixed across retries of this request, so a lost response is replayed, not re-applied.
        val key = inputData.getString(KEY) ?: UUID.randomUUID().toString()
        return when (container.push.register(deviceId, token, permission, BuildConfig.VERSION_NAME, key)) {
            PushRegisterResult.Registered -> Result.success().also { prefs.edit { putString(LAST_DIGEST, digest) } }
            PushRegisterResult.SessionExpired -> Result.success()
            is PushRegisterResult.Rejected -> Result.failure()
            is PushRegisterResult.Unavailable -> Result.retry()
        }
    }

    private suspend fun fcmToken(): String? = suspendCancellableCoroutine { cont ->
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task -> cont.resume(if (task.isSuccessful) task.result else null) }
    }

    companion object {
        private const val NAME = "push-registration"
        private const val PREFS = "push"
        private const val LAST_DIGEST = "last_registration_digest"
        private const val KEY = "idempotency_key"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<PushRegistrationWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setInputData(workDataOf(KEY to UUID.randomUUID().toString()))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { remove(LAST_DIGEST) }
        }
    }
}
