package com.digifender.adminifender.core.data.devicework

import com.digifender.adminifender.core.model.DeviceConditions
import kotlinx.serialization.Serializable

sealed interface DeviceCall<out T> {
    data class Ok<T>(val value: T) : DeviceCall<T>
    data object SessionExpired : DeviceCall<Nothing>
    data class Rejected(val code: String) : DeviceCall<Nothing>
    /** A mutation may have reached the server. Reuse its exact key/body for any retry. */
    data class Unavailable(val code: String) : DeviceCall<Nothing>
}

@Serializable
data class ReadinessRequest(
    val foreground: Boolean,
    val charging: Boolean,
    val batteryPercent: Int,
    val thermal: String,
    val modelId: String?,
    val modelManifestHash: String?,
) {
    companion object {
        fun from(conditions: DeviceConditions, model: LoadedDeviceModel?): ReadinessRequest {
            val known = conditions.batteryPercent != null && conditions.batteryPercent in 0..100 && conditions.charging != null && conditions.thermal != null
            return ReadinessRequest(
                conditions.foreground, conditions.charging == true,
                conditions.batteryPercent?.coerceIn(0, 100) ?: 0,
                conditions.thermal?.wire ?: "severe",
                if (known) model?.modelId else null, if (known) model?.manifestHash else null,
            )
        }
    }
}

@Serializable data class ReadinessResponse(val ready: Boolean)
@Serializable data class ClaimResponse(val work: InferenceWork? = null, val reason: String? = null)
@Serializable data class InferenceWork(
    val attemptId: String,
    val jobId: String,
    val sourceId: String? = null,
    val sourceRevision: Long? = null,
    val turnId: String? = null,
    val policyRevision: Long,
    val task: String,
    val inputDigest: String,
    val chunkIds: List<String>,
    val modelId: String,
    val leaseToken: String,
    val expiresAt: String,
)
@Serializable data class HeartbeatRequest(val leaseToken: String)
@Serializable data class HeartbeatResponse(val attemptId: String, val expiresAt: String)
@Serializable data class InferenceSubmission(
    val leaseToken: String,
    val inputDigest: String,
    val policyRevision: Long,
    val outputJson: String,
    val modelManifestHash: String,
)
@Serializable data class InferenceReceipt(val attemptId: String, val state: String, val repairQueued: Boolean? = null)

interface DeviceWorkApi {
    suspend fun readiness(body: ReadinessRequest, key: String): DeviceCall<ReadinessResponse>
    suspend fun claim(body: ReadinessRequest, key: String): DeviceCall<ClaimResponse>
    suspend fun heartbeat(attemptId: String, body: HeartbeatRequest, key: String): DeviceCall<HeartbeatResponse>
    suspend fun submit(attemptId: String, body: InferenceSubmission, key: String): DeviceCall<InferenceReceipt>
}
