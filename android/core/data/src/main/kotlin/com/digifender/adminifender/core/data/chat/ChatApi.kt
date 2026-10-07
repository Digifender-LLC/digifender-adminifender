package com.digifender.adminifender.core.data.chat

import com.digifender.adminifender.core.data.network.ReadResult
import kotlinx.serialization.Serializable

const val MAX_CHAT_QUESTION_CHARS = 4_000
private val CHAT_ID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
fun isChatId(id: String) = CHAT_ID.matches(id)

@Serializable
data class ChatScope(val kind: String = "all", val id: String? = null) {
    val key: String get() = "$kind:${id.orEmpty()}"
    fun valid() = if (kind == "all") id == null else kind in setOf("source", "concern", "service") && id?.let(::isChatId) == true
}

@Serializable
data class ChatThread(val id: String, val scope: ChatScope, val createdAt: String)

@Serializable
data class ChatCitation(
    val chunkId: String,
    val sourceId: String? = null,
    val attachmentId: String? = null,
    val page: Int? = null,
    val start: Int,
    val end: Int,
    val excerpt: String? = null,
)

@Serializable
data class ChatClaim(val text: String, val basis: String, val citations: List<ChatCitation>)

/** Descriptive suggestions only. This type deliberately exposes no action or dispatch function. */
@Serializable
data class ChatProposal(val kind: String, val targetId: String, val description: String)

@Serializable
data class ChatTurn(
    val turnId: String,
    val threadId: String,
    val scope: ChatScope,
    val content: String,
    val state: String,
    val reason: String? = null,
    val errorCode: String? = null,
    val answer: String? = null,
    val claims: List<ChatClaim> = emptyList(),
    val gaps: List<String> = emptyList(),
    val proposals: List<ChatProposal> = emptyList(),
    val invalidated: Boolean = false,
    val withheld: Boolean = false,
    val createdAt: String,
    val completedAt: String? = null,
) {
    val waiting: Boolean get() = state == "pending" || state == "running"

    /** Fail closed even if an older or malformed server response includes text it says to withhold. */
    fun forDisplay(): ChatTurn = if (invalidated || withheld || state != "complete") {
        copy(answer = null, claims = emptyList(), gaps = emptyList(), proposals = emptyList())
    } else this
}

@Serializable
data class ChatHistory(val thread: ChatThread, val turns: List<ChatTurn>, val hasMore: Boolean)

@Serializable
data class ChatAccepted(val turnId: String, val state: String)

sealed interface ChatWriteResult<out T> {
    data class Ok<T>(val value: T) : ChatWriteResult<T>
    data class Rejected(val code: String) : ChatWriteResult<Nothing>
    data object SessionExpired : ChatWriteResult<Nothing>
    /** A mutation may have committed. Recover its identity before another request. */
    data object NoAnswer : ChatWriteResult<Nothing>
}

interface ChatApi {
    suspend fun createThread(scope: ChatScope, key: String): ChatWriteResult<ChatThread>
    suspend fun submitTurn(threadId: String, turnId: String, content: String, key: String): ChatWriteResult<ChatAccepted>
    suspend fun turn(turnId: String): ReadResult<ChatTurn>
    suspend fun history(threadId: String, before: String? = null): ReadResult<ChatHistory>
    suspend fun cancel(turnId: String, key: String): ChatWriteResult<ChatAccepted>
}
