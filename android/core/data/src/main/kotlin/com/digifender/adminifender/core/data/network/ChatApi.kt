package com.digifender.adminifender.core.data.network

import kotlinx.serialization.Serializable

/** The question limit the Worker enforces on `content` (backend/src/chat/routes.ts). */
const val MAX_QUESTION_CHARS = 4000

/** What a conversation is about. Fixed when the thread is created and shown before any question is sent (FR-025). */
enum class ScopeKind(val wire: String) {
    ALL("all"),
    CONCERN("concern"),
    SOURCE("source"),
    SERVICE("service"),
    ;

    companion object {
        fun fromWire(value: String): ScopeKind? = entries.firstOrNull { it.wire == value }
    }
}

/** [id] is null exactly for [ScopeKind.ALL]. */
data class ChatScope(val kind: ScopeKind, val id: String? = null) {
    /** True when the shape is the one the Worker accepts: an ID for a concern, email or service, none for everything. */
    val wellFormed: Boolean get() = (kind == ScopeKind.ALL) == (id == null) && (id == null || isRecordId(id))
}

// Wire shapes below follow chat/routes.ts as implemented, not the wider LLD sketch: for example the turn request
// carries only `turnId` and `content` (the Worker rejects unknown fields, so `expectedScopeRevision` is never sent).

@Serializable
data class ScopeDto(val kind: String, val id: String? = null)

@Serializable
data class ThreadDto(val id: String, val scope: ScopeDto, val createdAt: String)

@Serializable
data class CitationDto(
    val chunkId: String,
    val sourceId: String? = null,
    val attachmentId: String? = null,
    val page: Int? = null,
    val start: Int,
    val end: Int,
    /** The cited characters as the Worker read them just now; null when the evidence is no longer readable. */
    val excerpt: String? = null,
)

@Serializable
data class ClaimDto(val text: String, val basis: String, val citations: List<CitationDto> = emptyList())

@Serializable
data class ProposalDto(val kind: String, val targetId: String, val description: String)

/** An email the model was given, with the privacy it had then (backend/src/chat/provenance.ts). */
@Serializable
data class ContextDto(val sourceId: String, val privacy: String)

@Serializable
data class TurnDto(
    val turnId: String,
    val threadId: String,
    val scope: ScopeDto,
    val content: String,
    val state: String,
    val reason: String? = null,
    val errorCode: String? = null,
    val answer: String? = null,
    val claims: List<ClaimDto> = emptyList(),
    val gaps: List<String> = emptyList(),
    val proposals: List<ProposalDto> = emptyList(),
    /**
     * Every email the answer rests on, not only the cited ones. Present exactly when an answer is; an empty list is a
     * no-evidence answer, and null with an answer means the Worker could not say, which the phone treats as unverifiable.
     */
    val context: List<ContextDto>? = null,
    val invalidated: Boolean = false,
    val withheld: Boolean = false,
    val createdAt: String,
    val completedAt: String? = null,
)

@Serializable
data class HistoryDto(
    val thread: ThreadDto,
    val turns: List<TurnDto>,
    val hasMore: Boolean,
    /**
     * The ID to send as `before` for the next page, in the Worker's own order: the oldest turn of this page while
     * [hasMore], otherwise null. The phone pages with this and never works an anchor out from its own record.
     */
    val nextBefore: String? = null,
)

@Serializable
data class TurnAcceptedDto(val turnId: String, val state: String)

/** What the Worker said about creating a thread. A thread costs nothing to create, so repeating the same key is always safe. */
sealed interface ThreadResult {
    data class Created(val thread: ThreadDto) : ThreadResult

    /** Refused for good ([code] is the server's fixed error code); no thread exists for this key. */
    data class Refused(val code: String) : ThreadResult

    /** No authoritative answer: the thread may or may not exist. Send the same key again. */
    data object NoAnswer : ThreadResult

    data object SessionExpired : ThreadResult
}

sealed interface PostResult {
    /** The turn is stored on the server, in [state]. */
    data class Accepted(val state: String) : PostResult

    /** Refused for good before anything was stored for this turn ID. */
    data class Refused(val code: String) : PostResult

    /** No authoritative answer: the turn may or may not be stored. Read it back before doing anything else. */
    data object NoAnswer : PostResult

    data object SessionExpired : PostResult
}

sealed interface CancelResult {
    data object Cancelled : CancelResult

    /** `already_dispatched`: the answer is being produced and cancelling cannot undo the processing. */
    data object AlreadyDispatched : CancelResult

    /** `not_cancellable`: the turn is no longer waiting ([state] is the server's current state when it said). */
    data class NotCancellable(val state: String?) : CancelResult

    data object NotFound : CancelResult

    data class Refused(val code: String) : CancelResult

    data object NoAnswer : CancelResult

    data object SessionExpired : CancelResult
}

/**
 * Authenticated chat calls (lld.md#chat). The turn ID is the request identity: [postTurn] uses it as the
 * Idempotency-Key too, so every resend of one question is byte-for-byte the same request. Nothing here retries.
 */
interface ChatApi {
    /** [idempotencyKey] is fixed per local thread, so a resend after a lost answer returns the stored thread. */
    suspend fun createThread(idempotencyKey: String, scope: ChatScope): ThreadResult

    suspend fun postTurn(threadId: String, turnId: String, content: String): PostResult

    /** Durable state of one turn; reading it never dispatches anything. A missing turn is `Unavailable("not_found")`. */
    suspend fun turn(turnId: String): ReadResult<TurnDto>

    /** The newest 50 turns of the thread, oldest first, or the 50 before [before] (a `nextBefore` the Worker gave) when it is given. */
    suspend fun history(threadId: String, before: String?): ReadResult<HistoryDto>

    suspend fun cancel(turnId: String, idempotencyKey: String): CancelResult
}
