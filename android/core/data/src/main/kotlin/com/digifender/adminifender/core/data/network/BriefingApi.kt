package com.digifender.adminifender.core.data.network

import kotlinx.serialization.Serializable

/**
 * The since-last-review snapshot (lld.md#briefings). The Worker also sends a `summary` that stays null until the
 * inference slice; it is not read here, so a later summary can never change what the phone shows or reviews.
 */
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
    /** True when the Worker left new or changed work out of a capped section; [snapshotSeq] then stops before it. */
    val hasMore: Boolean,
)

@Serializable
data class BriefingReviewedDto(val id: String, val reviewedThroughSeq: Long, val reviewedAt: String)

/** What the server said about marking a briefing reviewed, reduced to the cases the screen words differently. */
sealed interface ReviewResult {
    data class Reviewed(val id: String, val throughSeq: Long, val reviewedAtMs: Long) : ReviewResult

    /** The briefing was reviewed before, perhaps by an earlier send whose answer was lost. Nothing further happened. */
    data object AlreadyReviewed : ReviewResult

    /** The server has no such briefing. */
    data object NotFound : ReviewResult

    /** Refused for good; [code] is the server's fixed error code. */
    data class Rejected(val code: String) : ReviewResult

    /** No authoritative answer. The same key is safe to send again. */
    data object NoAnswer : ReviewResult

    data object SessionExpired : ReviewResult
}

/** Authenticated briefing calls. Nothing here changes a concern; only an explicit review advances the cursor. */
interface BriefingApi {
    suspend fun currentBriefing(): ReadResult<BriefingDto>

    /** Marks [id] reviewed through its own snapshot. [idempotencyKey] stays the same for every resend of one intent. */
    suspend fun reviewBriefing(id: String, idempotencyKey: String): ReviewResult
}

private val RECORD_ID = Regex("^[0-9a-f-]{36}$")

/** An ID from a response becomes a request path or a stored key, so its shape is checked first. */
fun isRecordId(id: String) = RECORD_ID.matches(id)

fun isBriefingId(id: String) = isRecordId(id)
