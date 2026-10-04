package com.digifender.adminifender.core.data.network

import kotlinx.serialization.Serializable

/** Grounded chat (lld.md#chat) and the owner's extraction resume; mutations carry an idempotency key. */
interface ChatApi {
    /** Creates a thread; [idempotencyKey] is kept by the caller so a lost answer can be resent safely. */
    suspend fun createThread(idempotencyKey: String, scope: ScopeDto): CallResult<ChatThreadDto>

    /** The turn ID is the question's identity and also its idempotency key (FR-027). */
    suspend fun postTurn(threadId: String, turnId: String, content: String): CallResult<TurnStateDto>

    suspend fun turn(turnId: String): ReadResult<TurnDto>

    suspend fun cancelTurn(turnId: String, idempotencyKey: String): CallResult<TurnStateDto>

    /** The owner's "Resume next pages" for a long document paused at the per-job page limit. */
    suspend fun resumeExtraction(attachmentId: String, idempotencyKey: String): CallResult<Unit>
}

/** Outcome of a mutation: answered, refused with a fixed code, or unknown because no answer arrived. */
sealed interface CallResult<out T> {
    data class Ok<T>(val value: T) : CallResult<T>

    data class Refused(val code: String) : CallResult<Nothing>

    data object NoAnswer : CallResult<Nothing>

    data object SessionExpired : CallResult<Nothing>
}

@Serializable
data class ScopeDto(val kind: String, val id: String? = null)

@Serializable
data class ChatThreadDto(val id: String, val scope: ScopeDto, val createdAt: String)

@Serializable
data class TurnStateDto(val turnId: String, val state: String)

@Serializable
data class CitationDto(
    val chunkId: String,
    val sourceId: String? = null,
    val attachmentId: String? = null,
    val page: Int? = null,
    val start: Int,
    val end: Int,
    val excerpt: String? = null,
)

@Serializable
data class ClaimDto(val text: String, val basis: String, val citations: List<CitationDto> = emptyList())

@Serializable
data class ProposalDto(val kind: String, val targetId: String, val description: String)

@Serializable
data class TurnDto(
    val turnId: String,
    val threadId: String,
    val content: String,
    val state: String,
    val reason: String? = null,
    val errorCode: String? = null,
    val answer: String? = null,
    val claims: List<ClaimDto> = emptyList(),
    val gaps: List<String> = emptyList(),
    val proposals: List<ProposalDto> = emptyList(),
    val invalidated: Boolean = false,
    val withheld: Boolean = false,
    val createdAt: String,
    val completedAt: String? = null,
)
