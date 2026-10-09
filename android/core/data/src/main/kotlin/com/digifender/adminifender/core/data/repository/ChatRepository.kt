package com.digifender.adminifender.core.data.repository

import com.digifender.adminifender.core.data.network.CancelResult
import com.digifender.adminifender.core.data.network.ChatApi
import com.digifender.adminifender.core.data.network.ChatScope
import com.digifender.adminifender.core.data.network.HistoryDto
import com.digifender.adminifender.core.data.network.MAX_QUESTION_CHARS
import com.digifender.adminifender.core.data.network.PostResult
import com.digifender.adminifender.core.data.network.ReadResult
import com.digifender.adminifender.core.data.network.ThreadResult
import com.digifender.adminifender.core.data.network.isRecordId
import java.time.format.DateTimeParseException
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The phone's chat store; Room in the app, in memory in tests. Every method is one transaction. Updates apply only to
 * rows that still exist, so a response that arrives after sign-out (tables cleared) or after a row was dropped cannot
 * bring anything back.
 */
interface ChatCache {
    fun threads(): Flow<List<ChatThread>>

    fun thread(localId: String): Flow<ChatThread?>

    /** The conversation's turns, oldest first. */
    fun turns(localId: String): Flow<List<ChatTurn>>

    suspend fun threadOnce(localId: String): ChatThread?

    /** The most recently used conversation for exactly [scope] that the Worker has not refused. */
    suspend fun latestThread(scope: ChatScope): ChatThread?

    suspend fun turnOnce(turnId: String): ChatTurn?

    suspend fun turnsOnce(localId: String): List<ChatTurn>

    /** Every turn that is in flight or held back only until its cited emails are on the phone. */
    suspend fun needingCheck(): List<ChatTurn>

    suspend fun insertThread(thread: ChatThread)

    /** Returns false if the conversation is gone. */
    suspend fun setDraft(localId: String, draft: String): Boolean

    suspend fun updateCreate(localId: String, state: CreateState, serverId: String?, error: String?): Boolean

    /** Stores [turn] ([Dispatch.QUEUED]) and empties its conversation's draft in one transaction. False if the conversation is gone. */
    suspend fun submit(turn: ChatTurn, nowMs: Long): Boolean

    /** [Dispatch.QUEUED] to [Dispatch.ATTEMPTED], written before the request is made. False if the turn is not queued. */
    suspend fun markAttempted(turnId: String): Boolean

    /** Sets [dispatch] only while the turn is in one of [from]. */
    suspend fun setDispatch(turnId: String, dispatch: Dispatch, error: String?, from: Set<Dispatch>): Boolean

    /** The Worker confirmed the POST: queued or attempted becomes accepted; [state] fills a state not yet known. */
    suspend fun accepted(turnId: String, state: ServerState?, nowMs: Long): Boolean

    suspend fun setCancelNote(turnId: String, note: CancelNote): Boolean

    /**
     * Puts a refused or withdrawn question's exact text back into its conversation's draft and removes the question, in
     * one transaction, but only when the draft is empty (or already that text). With a different draft in the way the
     * question stays where it is and nothing is lost. Other turns are never removed here.
     */
    suspend fun editAgain(turnId: String): EditAgain

    /** What sign-out would discard without the owner having sent or finished it. */
    fun unsent(): Flow<ChatUnsent>

    /**
     * Stores a page of the Worker's turns for one conversation under the rules of [planPage], including the check of
     * cited emails against the synced cache, all in one transaction. False if the conversation is gone.
     */
    suspend fun applyServer(threadLocalId: String, page: List<ServerTurn>, kind: PageKind, insertUnknown: Boolean, nowMs: Long): Boolean

    /**
     * After a sync changed or removed the emails in [changed]: removes the stored answer, for good, of every turn that
     * rests on one of them and no longer holds against the emails as they are now (see [staleAnswers]). Returns how
     * many answers were removed. Called inside the sync transaction, after the emails were written.
     */
    suspend fun dropRestrictedAnswers(changed: Set<String>): Int
}

sealed interface EditAgain {
    /** The question is gone from the screen and [text] is in the draft. */
    data class Restored(val text: String) : EditAgain

    /** The draft holds something else; the question stays so neither is lost. */
    data object DraftInUse : EditAgain

    data object Unavailable : EditAgain
}

sealed interface SendOutcome {
    data class Submitted(val turnId: String) : SendOutcome

    data object Empty : SendOutcome

    data object TooLong : SendOutcome

    /** The conversation was removed (sign-out) or the Worker refused its scope for good. */
    data object Gone : SendOutcome

    data object ScopeRefused : SendOutcome
}

/** How far one attempt to move a turn forward got. */
enum class Settle {
    /** Nothing more to do now: the Worker confirmed, refused for good, or the turn needs no sending. */
    DONE,

    /** No authoritative answer. The turn stays as it is and is read back (never re-sent blind) next time. */
    NOT_CONFIRMED,
    SIGN_IN,
    GONE,
}

sealed interface ReadOutcome {
    /** Read, checked and stored; the cache flow carries it. */
    data object Updated : ReadOutcome

    /** A newer read had already been stored; this one was dropped. */
    data object Stale : ReadOutcome

    /** The Worker does not have it, or the phone no longer holds the row it would update. */
    data object NotFound : ReadOutcome

    data object SignIn : ReadOutcome

    data object Offline : ReadOutcome

    /** The conversation has no Worker thread yet, so there is nothing to read. */
    data object NothingYet : ReadOutcome

    data class Unavailable(val code: String) : ReadOutcome
}

data class RecoverReport(
    /** Something is still moving: poll again soon. */
    val waiting: Boolean,
    val signIn: Boolean,
    /** At least one step got no answer. */
    val unreachable: Boolean,
)

sealed interface CancelOutcome {
    /** The Worker cancelled it and the cache now says so. */
    data object Cancelled : CancelOutcome

    /** The Worker had no record of it, so it will not be sent. */
    data object Withdrawn : CancelOutcome

    /** The answer is being produced; cancelling cannot undo that processing. */
    data object AlreadyDispatched : CancelOutcome

    data object NotCancellable : CancelOutcome

    /** No answer from the Worker; the turn was read back if possible, so look at its state. */
    data object NotConfirmed : CancelOutcome

    data object SignIn : CancelOutcome

    data object Gone : CancelOutcome

    data class Refused(val code: String) : CancelOutcome
}

private sealed interface ThreadStep {
    data class Ready(val serverId: String) : ThreadStep

    data class Refused(val code: String) : ThreadStep

    data class Stop(val settle: Settle) : ThreadStep
}

/**
 * Grounded chat (lld.md#chat, FR-025 to FR-028). What matters most:
 *
 * - A question is stored with its UUID, exact text and scope before anything is sent, and the stored step is written
 *   before the step it names ([Dispatch.ATTEMPTED] before the POST). The turn ID is the Worker's request identity.
 * - After a lost answer or process death the turn is read back first. Only a Worker "no such turn" lets the same
 *   request be sent again, with the same ID; a turn the Worker has is never sent again, so there is no second
 *   inference and no new ID. A new ID exists only for new content the owner submitted.
 * - Everything read from the Worker goes into the cache before the screen sees it, under generation and per-item
 *   ticket guards: a read that began before sign-out is dropped, and a slower read never replaces a newer one.
 */
class ChatRepository(
    private val api: ChatApi,
    private val cache: ChatCache,
    private val clock: () -> Long,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    /** Short lock around generation, tickets and the apply of a read; never held across a request. */
    private val guard = Mutex()

    /** One send, recovery or cancel at a time, so two callers cannot race to send the same turn. */
    private val dispatchLock = Mutex()
    private val openLock = Mutex()
    private var generation = 0
    private var nextTicket = 0L
    private val applied = HashMap<String, Long>()

    fun threads(): Flow<List<ChatThread>> = cache.threads()

    fun thread(localId: String): Flow<ChatThread?> = cache.thread(localId)

    fun turns(localId: String): Flow<List<ChatTurn>> = cache.turns(localId)

    /**
     * The conversation to use for [scope]: the latest one for exactly that scope, or a new local one. A new one makes
     * no request; the Worker's thread is created when the first question is sent.
     */
    suspend fun openThread(scope: ChatScope): String? {
        if (!scope.wellFormed) return null
        return openLock.withLock {
            cache.latestThread(scope)?.localId ?: newId().also { id ->
                val now = clock()
                cache.insertThread(ChatThread(id, newId(), null, scope, CreateState.NOT_CREATED, null, "", null, false, now, now))
            }
        }
    }

    suspend fun setDraft(localId: String, text: String): Boolean = cache.setDraft(localId, text)

    /**
     * Stores the question and empties the draft in one step. Nothing is sent: call [dispatch] next. The text is kept
     * exactly as typed.
     */
    suspend fun submit(localId: String, text: String): SendOutcome {
        if (text.isBlank()) return SendOutcome.Empty
        if (text.length > MAX_QUESTION_CHARS) return SendOutcome.TooLong
        val thread = cache.threadOnce(localId) ?: return SendOutcome.Gone
        if (thread.createState == CreateState.REFUSED) return SendOutcome.ScopeRefused
        val now = clock()
        val turn = ChatTurn(
            turnId = newId(),
            threadLocalId = localId,
            content = text,
            scope = thread.scope,
            dispatch = Dispatch.QUEUED,
            dispatchError = null,
            state = null,
            reason = null,
            errorCode = null,
            answer = null,
            serverInvalidated = false,
            serverWithheld = false,
            localInvalid = null,
            cancelNote = null,
            createdAtMs = now,
            completedAtMs = null,
            checkedAtMs = null,
        )
        return if (cache.submit(turn, now)) SendOutcome.Submitted(turn.turnId) else SendOutcome.Gone
    }

    /**
     * Moves one stored turn forward: creates the Worker thread if needed, then sends, or reads back an unconfirmed send.
     * Once started it finishes even if the screen that asked goes away: leaving the app is not cancelling.
     */
    suspend fun dispatch(turnId: String): Settle = dispatchLock.withLock { withContext(NonCancellable) { dispatchLocked(turnId) } }

    private suspend fun dispatchLocked(turnId: String): Settle {
        val turn = cache.turnOnce(turnId) ?: return Settle.GONE
        when (turn.dispatch) {
            Dispatch.ACCEPTED, Dispatch.REFUSED, Dispatch.WITHDRAWN -> return Settle.DONE
            Dispatch.ATTEMPTED -> return recoverAttempted(turn)
            Dispatch.QUEUED -> Unit
        }
        val thread = cache.threadOnce(turn.threadLocalId) ?: return Settle.GONE
        val serverId = when (val step = ensureThread(thread)) {
            is ThreadStep.Ready -> step.serverId
            is ThreadStep.Refused -> {
                cache.setDispatch(turnId, Dispatch.REFUSED, step.code, setOf(Dispatch.QUEUED))
                return Settle.DONE
            }
            is ThreadStep.Stop -> return step.settle
        }
        // The attempt is stored, and confirmed, before the request exists.
        if (!cache.markAttempted(turnId)) return Settle.GONE
        return post(turn, serverId)
    }

    private suspend fun post(turn: ChatTurn, serverId: String): Settle = when (val result = api.postTurn(serverId, turn.turnId, turn.content)) {
        is PostResult.Accepted -> {
            cache.accepted(turn.turnId, ServerState.fromWire(result.state), clock())
            Settle.DONE
        }
        is PostResult.Refused -> {
            cache.setDispatch(turn.turnId, Dispatch.REFUSED, result.code, setOf(Dispatch.QUEUED, Dispatch.ATTEMPTED))
            Settle.DONE
        }
        PostResult.NoAnswer -> Settle.NOT_CONFIRMED
        PostResult.SessionExpired -> Settle.SIGN_IN
    }

    /**
     * Creating a thread costs nothing, so an unconfirmed creation is simply repeated with the same Idempotency-Key.
     * That returns the stored thread if the first request got through. If the Worker no longer remembers the key (it
     * keeps them 90 days, and treats a request pending for over five minutes as abandoned) the worst case is one
     * empty thread nobody uses.
     */
    private suspend fun ensureThread(thread: ChatThread): ThreadStep {
        if (thread.createState == CreateState.CREATED && thread.serverId != null) return ThreadStep.Ready(thread.serverId)
        if (thread.createState == CreateState.REFUSED) return ThreadStep.Refused(thread.createError ?: "refused")
        if (thread.createState == CreateState.NOT_CREATED) cache.updateCreate(thread.localId, CreateState.UNCONFIRMED, null, null)
        return when (val result = api.createThread(thread.createKey, thread.scope)) {
            is ThreadResult.Created -> {
                val made = result.thread
                if (!isRecordId(made.id) || made.scope.kind != thread.scope.kind.wire || made.scope.id != thread.scope.id) {
                    // An answer about a different request or scope confirms nothing, and nothing is sent to it.
                    cache.updateCreate(thread.localId, CreateState.REFUSED, null, "mismatched_response")
                    ThreadStep.Refused("mismatched_response")
                } else {
                    cache.updateCreate(thread.localId, CreateState.CREATED, made.id, null)
                    ThreadStep.Ready(made.id)
                }
            }
            is ThreadResult.Refused -> {
                cache.updateCreate(thread.localId, CreateState.REFUSED, null, result.code)
                ThreadStep.Refused(result.code)
            }
            ThreadResult.NoAnswer -> ThreadStep.Stop(Settle.NOT_CONFIRMED)
            ThreadResult.SessionExpired -> ThreadStep.Stop(Settle.SIGN_IN)
        }
    }

    /** The POST may have reached the Worker. Read first; only "no such turn" allows the same request to be sent again. */
    private suspend fun recoverAttempted(turn: ChatTurn): Settle = when (readTurn(turn.turnId)) {
        ReadOutcome.Updated, ReadOutcome.Stale -> Settle.DONE
        ReadOutcome.NotFound -> {
            val serverId = cache.threadOnce(turn.threadLocalId)?.serverId
            if (cache.turnOnce(turn.turnId) == null) Settle.GONE else if (serverId == null) Settle.NOT_CONFIRMED else post(turn, serverId)
        }
        ReadOutcome.SignIn -> Settle.SIGN_IN
        ReadOutcome.Offline, ReadOutcome.NothingYet, is ReadOutcome.Unavailable -> Settle.NOT_CONFIRMED
    }

    /**
     * Checks everything that is moving or held back: sends stored questions, reads back unconfirmed ones, and refreshes
     * turns the Worker is still answering. Safe to call as often as the owner or the poll schedule likes.
     */
    suspend fun recover(): RecoverReport {
        var signIn = false
        var unreachable = false
        for (turn in cache.needingCheck()) {
            val ok = if (turn.dispatch == Dispatch.QUEUED || turn.dispatch == Dispatch.ATTEMPTED) {
                when (dispatch(turn.turnId)) {
                    Settle.DONE, Settle.GONE -> true
                    Settle.NOT_CONFIRMED -> false.also { unreachable = true }
                    Settle.SIGN_IN -> false.also { signIn = true }
                }
            } else {
                when (readTurn(turn.turnId)) {
                    ReadOutcome.Updated, ReadOutcome.Stale, ReadOutcome.NotFound, ReadOutcome.NothingYet -> true
                    ReadOutcome.SignIn -> false.also { signIn = true }
                    ReadOutcome.Offline, is ReadOutcome.Unavailable -> false.also { unreachable = true }
                }
            }
            if (!ok && signIn) break
        }
        return RecoverReport(cache.needingCheck().any { it.inFlight }, signIn, unreachable)
    }

    /** Reads one turn the phone started and stores it. Never creates a turn the phone does not hold. */
    suspend fun readTurn(turnId: String): ReadOutcome {
        val (startedIn, ticket) = guard.withLock { generation to ++nextTicket }
        val dto = when (val result = api.turn(turnId)) {
            is ReadResult.Ok -> result.value
            ReadResult.SessionExpired -> return ReadOutcome.SignIn
            ReadResult.CursorExpired -> return ReadOutcome.Unavailable("unexpected")
            is ReadResult.Unavailable -> return failed(result.code)
        }
        val server = try {
            dto.toServerTurn()
        } catch (_: IllegalArgumentException) {
            return ReadOutcome.Unavailable("malformed_response")
        } catch (_: DateTimeParseException) {
            return ReadOutcome.Unavailable("malformed_response")
        }
        if (server.turnId != turnId) return ReadOutcome.Unavailable("mismatched_response")
        return guard.withLock {
            val key = "turn:$turnId"
            val local = cache.turnOnce(turnId)
            val thread = local?.let { cache.threadOnce(it.threadLocalId) }
            when {
                generation != startedIn -> ReadOutcome.SignIn
                (applied[key] ?: 0L) > ticket -> ReadOutcome.Stale
                local == null || thread == null -> ReadOutcome.NotFound
                thread.serverId != server.serverThreadId -> ReadOutcome.Unavailable("mismatched_response")
                // The text and scope the owner submitted are the record; an echo that differs is about something else.
                server.content != local.content || server.scope != local.scope -> ReadOutcome.Unavailable("mismatched_response")
                else -> {
                    applied[key] = ticket
                    if (cache.applyServer(thread.localId, listOf(server), PageKind.Single, false, clock())) ReadOutcome.Updated else ReadOutcome.NotFound
                }
            }
        }
    }

    /** The newest page of the conversation's saved history (oldest first within the page). */
    suspend fun refreshHistory(localId: String): ReadOutcome = readHistory(localId, earlier = false)

    /** The page before the oldest one read, found by the cursor the Worker gave with it. */
    suspend fun loadEarlier(localId: String): ReadOutcome = readHistory(localId, earlier = true)

    private suspend fun readHistory(localId: String, earlier: Boolean): ReadOutcome {
        val thread = cache.threadOnce(localId) ?: return ReadOutcome.NotFound
        val serverId = thread.serverId ?: return ReadOutcome.NothingYet
        // The Worker's own cursor from the oldest page read so far; never an anchor worked out from the phone's rows or clock.
        val before = if (earlier) {
            thread.olderCursor ?: return ReadOutcome.NothingYet
        } else {
            null
        }
        val (startedIn, ticket) = guard.withLock { generation to ++nextTicket }
        val dto = when (val result = api.history(serverId, before)) {
            is ReadResult.Ok -> result.value
            ReadResult.SessionExpired -> return ReadOutcome.SignIn
            ReadResult.CursorExpired -> return ReadOutcome.Unavailable("unexpected")
            is ReadResult.Unavailable -> return failed(result.code)
        }
        val page = try {
            dto.turns.map { it.toServerTurn() }
        } catch (_: IllegalArgumentException) {
            return ReadOutcome.Unavailable("malformed_response")
        } catch (_: DateTimeParseException) {
            return ReadOutcome.Unavailable("malformed_response")
        }
        val scopeMatches = dto.thread.id == serverId && dto.thread.scope.kind == thread.scope.kind.wire && dto.thread.scope.id == thread.scope.id
        if (!scopeMatches || page.any { it.serverThreadId != serverId || it.scope != thread.scope }) return ReadOutcome.Unavailable("mismatched_response")
        val after = cursorAfter(dto, page, before) ?: return ReadOutcome.Unavailable("malformed_response")
        for (server in page) {
            val held = cache.turnOnce(server.turnId)
            if (held != null && held.threadLocalId == localId && (held.content != server.content || held.scope != server.scope)) return ReadOutcome.Unavailable("mismatched_response")
        }
        return guard.withLock {
            val key = "thread:$localId"
            when {
                generation != startedIn -> ReadOutcome.SignIn
                (applied[key] ?: 0L) > ticket -> ReadOutcome.Stale
                else -> {
                    applied[key] = ticket
                    val kind = if (before != null) PageKind.Earlier(before, after.value) else PageKind.Latest(after.value)
                    if (cache.applyServer(localId, page, kind, true, clock())) ReadOutcome.Updated else ReadOutcome.NotFound
                }
            }
        }
    }

    /** Where the next page starts: [value] is the Worker's `nextBefore`, or null when no earlier turns remain. */
    private class Cursor(val value: String?)

    /**
     * The cursor a history page leaves, or null when the page contradicts itself or the request, so it is not adopted.
     * A page with no turns leads nowhere whatever it claims, so it ends the history. Otherwise `hasMore` and `nextBefore`
     * must agree, `nextBefore` must be the page's own oldest turn, and a page asked for [before] a turn must not hold
     * that turn: it would ask for the same page again and never reach earlier ones.
     */
    private fun cursorAfter(dto: HistoryDto, page: List<ServerTurn>, before: String?): Cursor? {
        if (page.isEmpty()) return Cursor(null)
        val next = dto.nextBefore
        if (dto.hasMore != (next != null)) return null
        if (next != null && next != page.first().turnId) return null
        if (before != null && page.any { it.turnId == before }) return null
        return Cursor(next)
    }

    private fun failed(code: String): ReadOutcome = when (code) {
        "not_found" -> ReadOutcome.NotFound
        "network" -> ReadOutcome.Offline
        else -> ReadOutcome.Unavailable(code)
    }

    /**
     * Explicit cancel. Only work the Worker has not started can be cancelled; the Worker decides, and its refusals
     * are kept. A question that was never sent is withdrawn on the phone; one whose send is unconfirmed is read back
     * first, and withdrawn only if the Worker has no record of it.
     */
    suspend fun cancel(turnId: String): CancelOutcome = dispatchLock.withLock { withContext(NonCancellable) { cancelLocked(turnId) } }

    private suspend fun cancelLocked(turnId: String): CancelOutcome {
        val first = cache.turnOnce(turnId) ?: return CancelOutcome.Gone
        when (first.dispatch) {
            Dispatch.QUEUED -> {
                cache.setDispatch(turnId, Dispatch.WITHDRAWN, null, setOf(Dispatch.QUEUED))
                return CancelOutcome.Withdrawn
            }
            Dispatch.REFUSED, Dispatch.WITHDRAWN -> return CancelOutcome.NotCancellable
            Dispatch.ATTEMPTED -> when (readTurn(turnId)) {
                ReadOutcome.NotFound -> {
                    cache.setDispatch(turnId, Dispatch.WITHDRAWN, null, setOf(Dispatch.ATTEMPTED))
                    return CancelOutcome.Withdrawn
                }
                ReadOutcome.Updated, ReadOutcome.Stale -> Unit
                ReadOutcome.SignIn -> return CancelOutcome.SignIn
                else -> return CancelOutcome.NotConfirmed
            }
            Dispatch.ACCEPTED -> Unit
        }
        val turn = cache.turnOnce(turnId) ?: return CancelOutcome.Gone
        if (!turn.inFlight) {
            cache.setCancelNote(turnId, CancelNote.NOT_CANCELLABLE)
            return CancelOutcome.NotCancellable
        }
        return when (val result = api.cancel(turnId, newId())) {
            CancelResult.Cancelled -> {
                readTurn(turnId)
                CancelOutcome.Cancelled
            }
            CancelResult.AlreadyDispatched -> {
                cache.setCancelNote(turnId, CancelNote.ALREADY_DISPATCHED)
                readTurn(turnId)
                CancelOutcome.AlreadyDispatched
            }
            is CancelResult.NotCancellable -> {
                cache.setCancelNote(turnId, CancelNote.NOT_CANCELLABLE)
                readTurn(turnId)
                CancelOutcome.NotCancellable
            }
            CancelResult.NotFound -> CancelOutcome.Refused("not_found")
            is CancelResult.Refused -> CancelOutcome.Refused(result.code)
            CancelResult.NoAnswer -> {
                // The cancel may have been applied; the Worker's record says which.
                readTurn(turnId)
                CancelOutcome.NotConfirmed
            }
            CancelResult.SessionExpired -> CancelOutcome.SignIn
        }
    }

    /** Puts a refused or withdrawn question's text back into the draft, unless the draft holds something else. */
    suspend fun editAgain(turnId: String): EditAgain = cache.editAgain(turnId)

    fun unsent(): Flow<ChatUnsent> = cache.unsent()

    /**
     * Sign-out, best effort: asks the Worker to cancel every question it may hold that has not finished. Only work the
     * Worker has not started can be cancelled, a lost or refused request leaves the question running, and nothing is
     * waited for beyond each request, so this reports how many requests it made, never how many worked. Questions never
     * sent are not sent: they are discarded with the cache. Call before the session ends and the cache is cleared.
     */
    suspend fun cancelWaiting(): Int = dispatchLock.withLock {
        var asked = 0
        for (turn in cache.needingCheck()) {
            if (turn.dispatch != Dispatch.ATTEMPTED && !(turn.dispatch == Dispatch.ACCEPTED && turn.inFlight)) continue
            asked++
            if (api.cancel(turn.turnId, newId()) == CancelResult.SessionExpired) break
        }
        asked
    }

    /** Sign-out: reads and sends begun before this call can no longer write to the cache. Call before clearing it. */
    suspend fun invalidate() {
        guard.withLock {
            generation++
            applied.clear()
        }
    }
}
