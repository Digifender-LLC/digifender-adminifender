package com.digifender.adminifender.core.data.chat

import com.digifender.adminifender.core.data.network.ReadResult
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ChatSnapshot(
    val session: ChatSession,
    val turns: List<ChatTurn> = emptyList(),
    val hasMore: Boolean = false,
    val message: String? = null,
    val requestMissing: Boolean = false,
)

sealed interface ChatCitationResult {
    data class Available(val citation: ChatCitation) : ChatCitationResult
    data object Unavailable : ChatCitationResult
    data object SignIn : ChatCitationResult
}

/**
 * FR-027: persist before POST, recover by GET, and resend only an explicitly retried missing request with its
 * original identity and body. An uncertain inference is a terminal result, never a retry instruction.
 */
class ChatRepository(private val api: ChatApi, private val store: ChatStore, private val newId: () -> String = { UUID.randomUUID().toString() }) {
    private val mutex = Mutex()

    private suspend fun session(scope: ChatScope): ChatSession {
        require(scope.valid())
        return store.get(scope) ?: ChatSession(scope, newId()).also { store.put(it) }
    }

    suspend fun saveDraft(scope: ChatScope, text: String) = mutex.withLock {
        val current = session(scope)
        store.put(current.copy(draft = text.take(MAX_CHAT_QUESTION_CHARS)))
    }

    suspend fun load(scope: ChatScope, before: String? = null): ChatSnapshot = mutex.withLock {
        loadLocked(session(scope), before)
    }

    private suspend fun loadLocked(initial: ChatSession, before: String? = null): ChatSnapshot {
        var current = initial
        val thread = current.threadId ?: return ChatSnapshot(current)
        var message: String? = null
        var missing = false
        var requested: ChatTurn? = null
        current.requestId?.let { id ->
            when (val result = api.turn(id)) {
                is ReadResult.Ok -> {
                    val turn = result.value
                    if (!matches(turn, current, id) || turn.content != current.requestContent) {
                        message = "malformed_response"
                    } else {
                        requested = turn.forDisplay()
                        current = if (turn.waiting) current.copy(requestKnown = true)
                        else current.copy(requestId = null, requestContent = null, requestKnown = false)
                        store.put(current)
                    }
                }
                is ReadResult.Unavailable -> {
                    missing = result.code == "not_found" && !current.requestKnown
                    message = if (missing) "request_not_confirmed" else result.code
                }
                else -> message = "session_expired"
            }
        }
        return when (val result = api.history(thread, before)) {
            is ReadResult.Ok -> {
                val history = result.value
                if (history.thread.id != thread || history.thread.scope != current.scope || history.turns.size > 50 ||
                    history.turns.map { it.turnId }.distinct().size != history.turns.size || history.turns.any { !matches(it, current, it.turnId) }
                ) {
                    ChatSnapshot(current, message = "malformed_response", requestMissing = missing)
                } else {
                    val turns = history.turns.map(ChatTurn::forDisplay).toMutableList()
                    requested?.let { turn ->
                        val index = turns.indexOfFirst { it.turnId == turn.turnId }
                        if (index >= 0) turns[index] = turn else if (before == null) turns.add(turn)
                    }
                    ChatSnapshot(current, turns, history.hasMore, message, missing)
                }
            }
            else -> ChatSnapshot(current, listOfNotNull(requested), message = readCode(result), requestMissing = missing)
        }
    }

    suspend fun send(scope: ChatScope, content: String): ChatSnapshot = mutex.withLock {
        var current = session(scope)
        if (current.requestId != null) return@withLock loadLocked(current)
        val question = content.trim()
        if (question.isEmpty() || question.length > MAX_CHAT_QUESTION_CHARS) return@withLock ChatSnapshot(current, message = "invalid_question")
        if (current.threadId == null) {
            // The key is saved before creation; a lost create response cannot fork another thread on retry.
            when (val result = api.createThread(scope, current.threadKey)) {
                is ChatWriteResult.Ok -> {
                    if (result.value.scope != scope || !isChatId(result.value.id)) return@withLock ChatSnapshot(current, message = "malformed_response")
                    current = current.copy(threadId = result.value.id)
                    store.put(current)
                }
                else -> return@withLock ChatSnapshot(current, message = writeCode(result))
            }
        }
        current = current.copy(requestId = newId(), requestContent = question, requestKnown = false, draft = "")
        store.put(current)
        submitLocked(current)
    }

    /** Owner-requested recovery. A read must prove absence; a timeout or an existing turn never authorizes POST. */
    suspend fun retryMissing(scope: ChatScope): ChatSnapshot = mutex.withLock {
        val current = session(scope)
        val id = current.requestId ?: return@withLock loadLocked(current)
        when (val result = api.turn(id)) {
            is ReadResult.Unavailable -> if (result.code == "not_found" && !current.requestKnown) submitLocked(current)
                else loadLocked(current)
            else -> loadLocked(current)
        }
    }

    private suspend fun submitLocked(current: ChatSession): ChatSnapshot {
        val id = requireNotNull(current.requestId)
        val result = api.submitTurn(requireNotNull(current.threadId), id, requireNotNull(current.requestContent), id)
        when (result) {
            is ChatWriteResult.Ok -> {
                if (result.value.turnId == id) store.put(current.copy(requestKnown = true))
            }
            is ChatWriteResult.Rejected -> {
                // Only known deterministic refusals prove that this submission did not start inference.
                // In-progress, conflicting or unknown errors retain the identity for readback.
                if (result.code in setOf("invalid_request", "not_found", "scope_unavailable", "invalid_scope")) {
                    val restored = current.copy(draft = current.requestContent.orEmpty(), requestId = null, requestContent = null, requestKnown = false)
                    store.put(restored)
                    return loadLocked(restored).copy(message = result.code)
                }
            }
            else -> Unit
        }
        return loadLocked(store.get(current.scope) ?: current).let {
            if (result is ChatWriteResult.Ok) it else it.copy(message = it.message ?: writeCode(result))
        }
    }

    suspend fun cancel(scope: ChatScope, turnId: String): ChatSnapshot = mutex.withLock {
        val current = session(scope)
        // A fresh read prevents stale UI from labelling dispatched/uncertain work as cancelled.
        val read = api.turn(turnId)
        if (read !is ReadResult.Ok) return@withLock loadLocked(current).copy(message = readCode(read))
        if (!matches(read.value, current, turnId)) return@withLock ChatSnapshot(current, message = "malformed_response")
        if (read.value.state != "pending") return@withLock loadLocked(current).copy(message = "already_dispatched")
        val key = chatCancellationKey(turnId)
        val result = api.cancel(turnId, key)
        // Cancellation and a cancelled HTTP coroutine are different. Only server state proves cancellation.
        loadLocked(current).let { if (result is ChatWriteResult.Ok) it else it.copy(message = writeCode(result)) }
    }

    /** Citation navigation rechecks the turn, including deletion/quarantine, before exposing an excerpt. */
    suspend fun citation(scope: ChatScope, turnId: String, citation: ChatCitation): ChatCitationResult = mutex.withLock {
        val current = session(scope)
        when (val read = api.turn(turnId)) {
            ReadResult.SessionExpired -> ChatCitationResult.SignIn
            is ReadResult.Ok -> {
                val turn = read.value.forDisplay()
                val found = if (matches(turn, current, turnId)) turn.claims.flatMap { it.citations }.firstOrNull {
                    it.chunkId == citation.chunkId && it.start == citation.start && it.end == citation.end && it.sourceId == citation.sourceId
                } else null
                if (found?.sourceId?.let(::isChatId) == true && found.excerpt != null && found.start >= 0 && found.end > found.start && found.excerpt.length == found.end - found.start) {
                    ChatCitationResult.Available(found)
                } else ChatCitationResult.Unavailable
            }
            else -> ChatCitationResult.Unavailable
        }
    }

    /** Call on sign-out and authenticated owner reset, together with the ordinary local cache purge. */
    suspend fun clear() = mutex.withLock { store.clear() }

    private fun matches(turn: ChatTurn, current: ChatSession, id: String) =
        isChatId(turn.turnId) && turn.turnId == id && turn.threadId == current.threadId && turn.scope == current.scope &&
            turn.state in setOf("pending", "running", "complete", "failed", "uncertain", "cancelled")

    private fun readCode(result: ReadResult<*>): String = when (result) {
        ReadResult.SessionExpired -> "session_expired"
        is ReadResult.Unavailable -> result.code
        else -> "unavailable"
    }

    private fun writeCode(result: ChatWriteResult<*>): String = when (result) {
        ChatWriteResult.SessionExpired -> "session_expired"
        is ChatWriteResult.Rejected -> result.code
        ChatWriteResult.NoAnswer -> "request_not_confirmed"
        else -> "unavailable"
    }
}

/** A repeatable key in the backend's v4 UUID format, domain-separated from the random turn identity. */
internal fun chatCancellationKey(turnId: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest("chat-cancel:$turnId".toByteArray(Charsets.UTF_8))
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
    val buffer = ByteBuffer.wrap(bytes)
    return UUID(buffer.long, buffer.long).toString()
}
