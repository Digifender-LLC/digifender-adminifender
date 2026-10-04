package com.digifender.adminifender.core.data.repository

import com.digifender.adminifender.core.data.network.CallResult
import com.digifender.adminifender.core.data.network.ClaimDto
import com.digifender.adminifender.core.data.network.ChatApi
import com.digifender.adminifender.core.data.network.ReadResult
import com.digifender.adminifender.core.data.network.ScopeDto
import com.digifender.adminifender.core.data.network.TurnDto
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

/** What a thread may draw on; fixed for the thread and shown before any question is sent (FR-025). */
data class ChatScope(val kind: Kind, val id: String?) {
    enum class Kind(val wire: String) { ALL("all"), CONCERN("concern"), SOURCE("source") }

    companion object {
        val ALL = ChatScope(Kind.ALL, null)
    }
}

/**
 * Turn states. [SENDING] is local: the server has not confirmed it holds the question, so nothing claims it was
 * asked. The rest mirror the server's durable turn.
 */
enum class TurnState(val wire: String, val finished: Boolean) {
    SENDING("sending", false),
    PENDING("pending", false),
    RUNNING("running", false),
    COMPLETE("complete", true),
    FAILED("failed", true),
    CANCELLED("cancelled", true),
    UNCERTAIN("uncertain", true),
    REFUSED("refused", true),
    ;

    companion object {
        fun fromWire(value: String) = entries.firstOrNull { it.wire == value }
    }
}

data class ChatTurn(
    val turnId: String,
    val threadId: String,
    val content: String,
    val state: TurnState,
    val sent: Boolean,
    val reason: String?,
    val errorCode: String?,
    val answer: String?,
    val claims: List<ClaimDto>,
    val gaps: List<String>,
    val invalidated: Boolean,
    val withheld: Boolean,
    val createdAtMs: Long,
    val completedAtMs: Long?,
)

interface ChatCache {
    suspend fun thread(scope: ChatScope): String?

    suspend fun putThread(id: String, scope: ChatScope, createdAtMs: Long)

    fun turns(threadId: String): Flow<List<ChatTurn>>

    suspend fun turn(id: String): ChatTurn?

    /** Inserts a new local turn; a turn ID is never reused. */
    suspend fun insert(turn: ChatTurn)

    suspend fun put(turn: ChatTurn)

    suspend fun unfinished(): List<ChatTurn>
}

sealed interface ThreadResult {
    data class Ready(val threadId: String) : ThreadResult

    data class Refused(val code: String) : ThreadResult

    data object NoAnswer : ThreadResult

    data object SessionExpired : ThreadResult
}

sealed interface AskResult {
    data class Accepted(val turnId: String) : AskResult

    data object Empty : AskResult

    data object TooLong : AskResult
}

/**
 * Grounded chat (lld.md#chat). The client turn ID is the question's identity: a lost answer is recovered by
 * reading the turn, and a resend with the same ID never asks twice. Answers are read back from the server and
 * stored before they are shown; nothing here invents a result.
 */
class ChatRepository(
    private val cache: ChatCache,
    private val api: ChatApi,
    private val clock: () -> Long,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    // One creation key per scope for this process, so a retried creation after a lost answer is the same request.
    private val threadKeys = mutableMapOf<ChatScope, String>()

    /** The newest thread for [scope], creating one on the server when none is cached. */
    suspend fun openThread(scope: ChatScope): ThreadResult {
        cache.thread(scope)?.let { return ThreadResult.Ready(it) }
        val key = synchronized(threadKeys) { threadKeys.getOrPut(scope, newId) }
        return when (val r = api.createThread(key, ScopeDto(scope.kind.wire, scope.id))) {
            is CallResult.Ok -> {
                cache.putThread(r.value.id, scope, parse(r.value.createdAt) ?: clock())
                synchronized(threadKeys) { threadKeys.remove(scope) }
                ThreadResult.Ready(r.value.id)
            }
            is CallResult.Refused -> ThreadResult.Refused(r.code)
            CallResult.NoAnswer -> ThreadResult.NoAnswer
            CallResult.SessionExpired -> ThreadResult.SessionExpired
        }
    }

    fun turns(threadId: String): Flow<List<ChatTurn>> = cache.turns(threadId)

    /** Stores the question first, then sends it and follows its answer. */
    suspend fun ask(threadId: String, content: String): AskResult {
        val text = content.trim()
        if (text.isEmpty()) return AskResult.Empty
        if (text.length > MAX_QUESTION_CHARS) return AskResult.TooLong
        val turnId = newId()
        cache.insert(ChatTurn(turnId, threadId, text, TurnState.SENDING, false, null, null, null, emptyList(), emptyList(), false, false, clock(), null))
        send(turnId)
        return AskResult.Accepted(turnId)
    }

    /**
     * Sends an unconfirmed question with its original turn ID. Called on the owner's request after no answer
     * arrived; the server returns the stored turn if the first attempt did reach it.
     */
    suspend fun send(turnId: String) {
        val turn = cache.turn(turnId) ?: return
        if (turn.sent) return follow(turnId)
        when (val r = api.postTurn(turn.threadId, turn.turnId, turn.content)) {
            is CallResult.Ok -> {
                cache.put(turn.copy(sent = true, state = TurnState.fromWire(r.value.state) ?: TurnState.PENDING, errorCode = null))
                follow(turnId)
            }
            is CallResult.Refused -> cache.put(turn.copy(state = TurnState.REFUSED, errorCode = r.code, completedAtMs = clock()))
            CallResult.NoAnswer -> cache.put(turn.copy(errorCode = NO_ANSWER))
            CallResult.SessionExpired -> cache.put(turn.copy(errorCode = SIGN_IN))
        }
    }

    /** Polls a sent turn with backoff (1, 2, 5, 10 seconds); later checks happen on foreground or sync. */
    suspend fun follow(turnId: String) {
        for (wait in POLL_BACKOFF_MS) {
            sleep(wait)
            val turn = refresh(turnId) ?: return
            if (turn.state.finished) return
        }
    }

    /** Reads the server's copy of one turn into the cache. Unsent turns are checked but never resent here. */
    suspend fun refresh(turnId: String): ChatTurn? {
        val turn = cache.turn(turnId) ?: return null
        return when (val r = api.turn(turnId)) {
            is ReadResult.Ok -> merge(turn, r.value).also { cache.put(it) }
            // An unsent question the server does not know stays unsent until the owner sends it again.
            else -> turn
        }
    }

    /** Foreground or sync catch-up for every unfinished turn. */
    suspend fun refreshUnfinished() {
        cache.unfinished().forEach { refresh(it.turnId) }
    }

    /** Cancels a question that has not been dispatched. Work already dispatched cannot be undone. */
    suspend fun cancel(turnId: String): CallResult<Unit> {
        val turn = cache.turn(turnId) ?: return CallResult.Refused("not_found")
        if (!turn.sent) {
            // The server may hold it if a send was lost; ask it first, so a cancel is never claimed locally only.
            val known = api.turn(turnId)
            if (known is ReadResult.Unavailable && known.code == "not_found") {
                cache.put(turn.copy(state = TurnState.CANCELLED, completedAtMs = clock(), reason = "owner_cancelled"))
                return CallResult.Ok(Unit)
            }
            if (known !is ReadResult.Ok) return CallResult.NoAnswer
        }
        return when (val r = api.cancelTurn(turnId, newId())) {
            is CallResult.Ok -> {
                refresh(turnId)
                CallResult.Ok(Unit)
            }
            is CallResult.Refused -> r.also { refresh(turnId) }
            CallResult.NoAnswer -> CallResult.NoAnswer
            CallResult.SessionExpired -> CallResult.SessionExpired
        }
    }

    private fun merge(turn: ChatTurn, dto: TurnDto): ChatTurn = turn.copy(
        sent = true,
        state = TurnState.fromWire(dto.state) ?: turn.state,
        reason = dto.reason,
        errorCode = dto.errorCode,
        answer = dto.answer,
        claims = dto.claims,
        gaps = dto.gaps,
        invalidated = dto.invalidated,
        withheld = dto.withheld,
        completedAtMs = parse(dto.completedAt),
    )

    companion object {
        const val MAX_QUESTION_CHARS = 4000
        const val NO_ANSWER = "no_answer"
        const val SIGN_IN = "sign_in_required"
        val POLL_BACKOFF_MS = listOf(1_000L, 2_000L, 5_000L, 10_000L)

        private fun parse(value: String?) = value?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
    }
}
