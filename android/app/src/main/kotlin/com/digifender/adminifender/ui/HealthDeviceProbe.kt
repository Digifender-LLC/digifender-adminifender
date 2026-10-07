package com.digifender.adminifender.ui

import android.app.NotificationManager
import android.content.Context
import com.digifender.adminifender.core.data.devicework.DeviceConditionsSource
import com.digifender.adminifender.core.data.devicework.DeviceWorkState
import com.digifender.adminifender.core.data.devicework.LocalRuntimeStatus
import com.digifender.adminifender.core.data.health.HealthStatus
import com.digifender.adminifender.core.model.DeviceConditions
import com.digifender.adminifender.core.model.NotificationChannelKind

enum class HealthWorkerStatus { IDLE, DEFERRED, SIGN_IN_REQUIRED, RUNNING, FINISHED, UNCERTAIN, UNAVAILABLE }
data class HealthDeviceSnapshot(
    val notificationsEnabled: Boolean? = null,
    val channels: Map<NotificationChannelKind, Boolean?> = emptyMap(),
    val conditions: DeviceConditions? = null,
    val model: HealthStatus = HealthStatus.UNOBSERVED,
    val worker: HealthWorkerStatus = HealthWorkerStatus.UNAVAILABLE,
)
fun interface HealthDeviceProbe { fun read(): HealthDeviceSnapshot }

/** OS observations and verified runtime state only; worker idle never means a model is loaded. */
class AndroidHealthDeviceProbe(
    context: Context,
    private val conditions: DeviceConditionsSource,
    private val runtime: () -> LocalRuntimeStatus? = { null },
    private val worker: () -> DeviceWorkState = { DeviceWorkState.Idle },
) : HealthDeviceProbe {
    private val app = context.applicationContext
    override fun read(): HealthDeviceSnapshot {
        val notifications = app.getSystemService(NotificationManager::class.java)
        return HealthDeviceSnapshot(
            notificationsEnabled = runCatching { notifications?.areNotificationsEnabled() }.getOrNull(),
            channels = NotificationChannelKind.entries.associateWith { kind -> runCatching { notifications?.getNotificationChannel(kind.id)?.let { it.importance != NotificationManager.IMPORTANCE_NONE } }.getOrNull() },
            conditions = runCatching { conditions.current() }.getOrNull(),
            model = when (runCatching { runtime() }.getOrNull()) {
                is LocalRuntimeStatus.Ready -> HealthStatus.OK
                is LocalRuntimeStatus.Unavailable -> HealthStatus.UNAVAILABLE
                null -> HealthStatus.UNOBSERVED
            },
            worker = when (runCatching { worker() }.getOrNull()) {
                DeviceWorkState.Idle -> HealthWorkerStatus.IDLE
                is DeviceWorkState.Deferred -> HealthWorkerStatus.DEFERRED
                DeviceWorkState.SignInRequired -> HealthWorkerStatus.SIGN_IN_REQUIRED
                is DeviceWorkState.Running -> HealthWorkerStatus.RUNNING
                is DeviceWorkState.Finished -> HealthWorkerStatus.FINISHED
                is DeviceWorkState.SubmissionUnknown -> HealthWorkerStatus.UNCERTAIN
                else -> HealthWorkerStatus.UNAVAILABLE
            },
        )
    }
}
