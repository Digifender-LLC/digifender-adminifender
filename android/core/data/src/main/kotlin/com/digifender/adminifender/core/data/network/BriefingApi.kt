package com.digifender.adminifender.core.data.network

import kotlinx.serialization.Serializable

interface BriefingApi {
    suspend fun current(): ReadResult<BriefingDto>
    suspend fun review(id: String, idempotencyKey: String): BriefingReviewResult
}

@Serializable
data class BriefingDto(
    val id: String,
    val snapshotSeq: Long,
    val throughTime: String,
    val lastReviewedAt: String? = null,
    val newConcerns: List<ConcernDto>,
    val changedConcerns: List<ConcernDto>,
    val overdue: List<ConcernDto>,
    val unacknowledged: List<ConcernDto>,
    val newSourceIds: List<String>,
    val hasMore: Boolean = false,
)

@Serializable
data class BriefingReviewDto(val id: String, val reviewedThroughSeq: Long, val reviewedAt: String)

sealed interface BriefingReviewResult {
    data class Reviewed(val receipt: BriefingReviewDto) : BriefingReviewResult
    data object AlreadyReviewed : BriefingReviewResult
    data object SessionExpired : BriefingReviewResult
    data class Rejected(val code: String) : BriefingReviewResult
    /** The server may have committed. Only the same idempotency key may be retried. */
    data object NoAnswer : BriefingReviewResult
}
