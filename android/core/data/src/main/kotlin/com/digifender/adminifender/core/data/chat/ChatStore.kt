package com.digifender.adminifender.core.data.chat

/** Only drafts and request identities survive process death. Answers and cited excerpts are read live. */
data class ChatSession(
    val scope: ChatScope,
    val threadKey: String,
    val threadId: String? = null,
    val draft: String = "",
    val requestId: String? = null,
    val requestContent: String? = null,
    val requestKnown: Boolean = false,
)

interface ChatStore {
    suspend fun get(scope: ChatScope): ChatSession?
    suspend fun put(session: ChatSession)
    suspend fun clear()
}
