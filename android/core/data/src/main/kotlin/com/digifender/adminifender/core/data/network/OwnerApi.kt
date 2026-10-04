package com.digifender.adminifender.core.data.network

import java.io.OutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Briefing, reviewed effects, devices and exports (lld.md#briefings, lld.md#actions, lld.md#deletion-and-privacy-propagation). */
interface OwnerApi {
    suspend fun briefing(): ReadResult<BriefingDto>

    /** P2 operations overview; `feature_disabled` when the deployment has reports off. */
    suspend fun operations(): ReadResult<OperationsDto>

    suspend fun reviewBriefing(id: String, idempotencyKey: String): CallResult<Unit>

    suspend fun devices(): ReadResult<DevicesDto>

    suspend fun createReview(idempotencyKey: String, effect: String, params: JsonObject): CallResult<ReviewDto>

    suspend fun confirmReview(id: String, idempotencyKey: String, paramsDigest: String): CallResult<OperationDto>

    suspend fun createExport(idempotencyKey: String): CallResult<ExportDto>

    suspend fun export(id: String): ReadResult<ExportDto>

    /** Streams a ready export to [out]; never buffered whole in memory. */
    suspend fun saveExport(id: String, out: OutputStream): StreamResult

    /** Streams an attachment to [out], refusing more than [maxBytes] and checking its SHA-256. */
    suspend fun saveAttachment(id: String, out: OutputStream, maxBytes: Long, sha256: String): StreamResult
}

enum class StreamResult { OK, WRONG_HASH, TOO_LARGE, GONE, EXPIRED, SESSION_EXPIRED, UNAVAILABLE }

@Serializable
data class BriefingDto(
    val id: String,
    val snapshotSeq: Long,
    val throughTime: String,
    val lastReviewedAt: String? = null,
    val newConcerns: List<ConcernDto> = emptyList(),
    val changedConcerns: List<ConcernDto> = emptyList(),
    val overdue: List<ConcernDto> = emptyList(),
    val unacknowledged: List<ConcernDto> = emptyList(),
    val newSourceIds: List<String> = emptyList(),
)

@Serializable
data class DeviceDto(val id: String, val name: String, val lastSeen: String? = null, val revoked: Boolean, val push: String, val current: Boolean)

@Serializable
data class DevicesDto(val devices: List<DeviceDto>)

@Serializable
data class ReviewDto(val reviewId: String, val effect: String, val paramsDigest: String, val preview: JsonElement, val expiresAt: String)

@Serializable
data class OperationDto(val operationId: String, val state: String, val receipt: JsonElement? = null)

@Serializable
data class ExportDto(val id: String, val state: String, val expiresAt: String, val counts: JsonElement? = null, val omissions: JsonElement? = null)

@Serializable
data class CoverageDto(val scope: String, val status: String, val omittedCount: Int? = null)

@Serializable
data class ServiceReportDto(
    val kind: String,
    val concernKey: String,
    val summary: String,
    val coverage: CoverageDto,
    val reportedAt: String,
    val receivedAt: String,
    val freshness: String,
    val sourceIds: List<String> = emptyList(),
)

@Serializable
data class ServiceStatusDto(val id: String, val name: String, val freshness: String, val expectedEverySeconds: Long, val reports: List<ServiceReportDto> = emptyList())

@Serializable
data class ReportIssueDto(val sourceId: String, val serviceId: String? = null, val code: String, val createdAt: String)

@Serializable
data class OperationsDto(val services: List<ServiceStatusDto> = emptyList(), val verificationIssues: List<ReportIssueDto> = emptyList())
