package com.digifender.adminifender.ui

import com.digifender.adminifender.core.data.network.CancelResult
import com.digifender.adminifender.core.data.network.ChatApi
import com.digifender.adminifender.core.data.network.ChatScope
import com.digifender.adminifender.core.data.network.CitationDto
import com.digifender.adminifender.core.data.network.ClaimDto
import com.digifender.adminifender.core.data.network.ContextDto
import com.digifender.adminifender.core.data.network.HistoryDto
import com.digifender.adminifender.core.data.network.PostResult
import com.digifender.adminifender.core.data.network.ProposalDto
import com.digifender.adminifender.core.data.network.ReadResult
import com.digifender.adminifender.core.data.network.ScopeDto
import com.digifender.adminifender.core.data.network.ThreadDto
import com.digifender.adminifender.core.data.network.ThreadResult
import com.digifender.adminifender.core.data.network.TurnDto
import com.digifender.adminifender.core.data.repository.CancelNote
import com.digifender.adminifender.core.data.repository.ChatCache
import com.digifender.adminifender.core.data.repository.ChatThread
import com.digifender.adminifender.core.data.repository.ChatUnsent
import com.digifender.adminifender.core.data.repository.ChatTurn
import com.digifender.adminifender.core.data.repository.ContextSource
import com.digifender.adminifender.core.data.repository.CreateState
import com.digifender.adminifender.core.data.repository.DeleteThread
import com.digifender.adminifender.core.data.repository.Dispatch
import com.digifender.adminifender.core.data.repository.EditAgain
import com.digifender.adminifender.core.data.repository.LocalInvalid
import com.digifender.adminifender.core.data.repository.PageKind
import com.digifender.adminifender.core.data.repository.ServerState
import com.digifender.adminifender.core.data.repository.ServerTurn
import com.digifender.adminifender.core.data.repository.SourceFact
import com.digifender.adminifender.core.data.repository.inFlight
import com.digifender.adminifender.core.data.repository.contextSourceIds
import com.digifender.adminifender.core.data.repository.needsCheck
import com.digifender.adminifender.core.data.repository.planPage
import com.digifender.adminifender.core.data.repository.staleAnswers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

const val SERVER_THREAD = "7c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f"
const val SOURCE_1 = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
const val SOURCE_2 = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
const val OTHER_SOURCE = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"

/** The emails a model was given, each with the privacy it had (hybrid unless said otherwise). */
fun context(vararg ids: String, privacy: String = "hybrid") = ids.map { ContextSource(it, privacy) }

/** Same semantics as RoomChatCache: every call applies fully or not at all, and updates need the row to still exist. */
class InMemoryChatCache : ChatCache {
    private val threadRows = MutableStateFlow<List<ChatThread>>(emptyList())
    private val turnRows = MutableStateFlow<List<ChatTurn>>(emptyList())

    /** The synced emails, as the cache would read them when judging a cited answer. */
    val sources = mutableMapOf<String, SourceFact>()

    /** Reads a row without suspending, for hooks that run inside a fake's call. */
    fun peekTurn(turnId: String) = turnRows.value.firstOrNull { it.turnId == turnId }

    /** Sign-out: every table is cleared. */
    fun clear() {
        threadRows.value = emptyList()
        turnRows.value = emptyList()
    }

    override fun threads() = threadRows.map { rows -> rows.sortedByDescending { it.updatedAtMs } }

    override fun thread(localId: String) = threadRows.map { rows -> rows.firstOrNull { it.localId == localId } }

    override fun turns(localId: String) = turnRows.map { rows -> rows.filter { it.threadLocalId == localId }.sortedBy { it.createdAtMs } }

    override suspend fun threadOnce(localId: String) = threadRows.value.firstOrNull { it.localId == localId }

    override suspend fun latestThread(scope: ChatScope) = threadRows.value.filter { it.scope == scope && it.createState != CreateState.REFUSED }.maxByOrNull { it.updatedAtMs }

    override suspend fun turnOnce(turnId: String) = turnRows.value.firstOrNull { it.turnId == turnId }

    override suspend fun turnsOnce(localId: String) = turnRows.value.filter { it.threadLocalId == localId }.sortedBy { it.createdAtMs }

    override suspend fun needingCheck() = turnRows.value.filter { it.needsCheck }

    override suspend fun insertThread(thread: ChatThread) {
        threadRows.value = threadRows.value + thread
    }

    private fun updateThread(localId: String, change: (ChatThread) -> ChatThread): Boolean {
        if (threadRows.value.none { it.localId == localId }) return false
        threadRows.value = threadRows.value.map { if (it.localId == localId) change(it) else it }
        return true
    }

    private fun updateTurn(turnId: String, change: (ChatTurn) -> ChatTurn?): Boolean {
        val current = turnRows.value.firstOrNull { it.turnId == turnId } ?: return false
        val next = change(current) ?: return false
        turnRows.value = turnRows.value.map { if (it.turnId == turnId) next else it }
        return true
    }

    override suspend fun setDraft(localId: String, draft: String) = updateThread(localId) { it.copy(draft = draft) }

    override suspend fun updateCreate(localId: String, state: CreateState, serverId: String?, error: String?) =
        updateThread(localId) { it.copy(createState = state, serverId = serverId, createError = error) }

    override suspend fun submit(turn: ChatTurn, nowMs: Long): Boolean {
        if (!updateThread(turn.threadLocalId) { it.copy(draft = "", updatedAtMs = nowMs) }) return false
        turnRows.value = turnRows.value + turn
        return true
    }

    override suspend fun markAttempted(turnId: String) = updateTurn(turnId) { if (it.dispatch == Dispatch.QUEUED) it.copy(dispatch = Dispatch.ATTEMPTED) else null }

    override suspend fun setDispatch(turnId: String, dispatch: Dispatch, error: String?, from: Set<Dispatch>) =
        updateTurn(turnId) { if (it.dispatch in from) it.copy(dispatch = dispatch, dispatchError = error) else null }

    override suspend fun accepted(turnId: String, state: ServerState?, nowMs: Long) = updateTurn(turnId) {
        if (it.dispatch == Dispatch.QUEUED || it.dispatch == Dispatch.ATTEMPTED) it.copy(dispatch = Dispatch.ACCEPTED, dispatchError = null, state = it.state ?: state, checkedAtMs = nowMs) else null
    }

    override suspend fun setCancelNote(turnId: String, note: CancelNote) = updateTurn(turnId) { it.copy(cancelNote = note) }

    override suspend fun editAgain(turnId: String): EditAgain {
        val row = turnRows.value.firstOrNull { it.turnId == turnId } ?: return EditAgain.Unavailable
        if (row.dispatch != Dispatch.REFUSED && row.dispatch != Dispatch.WITHDRAWN) return EditAgain.Unavailable
        val thread = threadRows.value.firstOrNull { it.localId == row.threadLocalId } ?: return EditAgain.Unavailable
        if (thread.draft.isNotBlank() && thread.draft != row.content) return EditAgain.DraftInUse
        updateThread(thread.localId) { it.copy(draft = row.content) }
        turnRows.value = turnRows.value.filter { it.turnId != turnId }
        return EditAgain.Restored(row.content)
    }

    override suspend fun deleteThread(localId: String): DeleteThread {
        if (threadRows.value.none { it.localId == localId }) return DeleteThread.Unavailable
        if (turnRows.value.any { it.threadLocalId == localId && it.inFlight() }) return DeleteThread.InFlight
        turnRows.value = turnRows.value.filter { it.threadLocalId != localId }
        threadRows.value = threadRows.value.filter { it.localId != localId }
        return DeleteThread.Deleted
    }

    override fun unsent(): Flow<ChatUnsent> = combine(threadRows, turnRows) { threads, turns ->
        ChatUnsent(
            unconfirmedQuestions = turns.count { it.dispatch == Dispatch.QUEUED || it.dispatch == Dispatch.ATTEMPTED },
            drafts = threads.count { it.draft.isNotBlank() },
            atWorker = turns.count { it.dispatch == Dispatch.ACCEPTED && (it.state == null || it.state == ServerState.PENDING || it.state == ServerState.RUNNING) },
        )
    }

    override suspend fun applyServer(threadLocalId: String, page: List<ServerTurn>, kind: PageKind, insertUnknown: Boolean, nowMs: Long): Boolean {
        val thread = threadRows.value.firstOrNull { it.localId == threadLocalId } ?: return false
        val existing = turnRows.value.filter { t -> page.any { it.turnId == t.turnId } }.associateBy { it.turnId }
        val facts = contextSourceIds(page).mapNotNull { id -> sources[id]?.let { id to it } }.toMap()
        val plan = planPage(thread, existing, page, kind, insertUnknown, facts, nowMs)
        for (turn in plan.upserts) {
            turnRows.value = if (turnRows.value.any { it.turnId == turn.turnId }) turnRows.value.map { if (it.turnId == turn.turnId) turn else it } else turnRows.value + turn
        }
        updateThread(threadLocalId) { it.copy(olderCursor = plan.olderCursor, historyLoaded = plan.historyLoaded) }
        return true
    }

    override suspend fun dropRestrictedAnswers(changed: Set<String>): Int {
        val ids = staleAnswers(changed, turnRows.value, sources)
        turnRows.value = turnRows.value.map { if (it.turnId in ids) it.copy(answer = null, localInvalid = LocalInvalid.SOURCE_CHANGED) else it }
        return ids.size
    }
}

/** Scripted Worker: queued answers per call and a log of every request, in order. */
class FakeChatApi : ChatApi {
    val log = mutableListOf<String>()
    val threadKeys = mutableListOf<String>()
    val posted = mutableListOf<Triple<String, String, String>>()
    val cancelKeys = mutableListOf<String>()
    val historyBefore = mutableListOf<String?>()
    val threadResults = ArrayDeque<ThreadResult>()
    val postResults = ArrayDeque<PostResult>()
    val turnReads = ArrayDeque<suspend () -> ReadResult<TurnDto>>()

    /** Answers every turn read once [turnReads] is empty. */
    var defaultTurn: (suspend () -> ReadResult<TurnDto>)? = null
    val historyReads = ArrayDeque<suspend () -> ReadResult<HistoryDto>>()
    val cancelResults = ArrayDeque<CancelResult>()

    /** Runs when a POST arrives, to look at what the phone had stored by then. */
    var onPost: (String) -> Unit = {}

    /** Holds every POST until completed, to put other work alongside a send that is in flight. */
    var postGate: CompletableDeferred<Unit>? = null

    override suspend fun createThread(idempotencyKey: String, scope: ChatScope): ThreadResult {
        log += "thread"
        threadKeys += idempotencyKey
        return threadResults.removeFirst()
    }

    override suspend fun postTurn(threadId: String, turnId: String, content: String): PostResult {
        log += "post"
        posted += Triple(threadId, turnId, content)
        onPost(turnId)
        postGate?.await()
        return postResults.removeFirst()
    }

    override suspend fun turn(turnId: String): ReadResult<TurnDto> {
        log += "turn"
        return (turnReads.removeFirstOrNull() ?: defaultTurn ?: error("unscripted turn read"))()
    }

    override suspend fun history(threadId: String, before: String?): ReadResult<HistoryDto> {
        log += "history"
        historyBefore += before
        return historyReads.removeFirst()()
    }

    override suspend fun cancel(turnId: String, idempotencyKey: String): CancelResult {
        log += "cancel"
        cancelKeys += idempotencyKey
        return cancelResults.removeFirst()
    }
}

fun created(id: String = SERVER_THREAD, scope: ScopeDto = ScopeDto("all")) = ThreadResult.Created(ThreadDto(id, scope, "2026-10-08T10:00:00.000Z"))

fun citation(sourceId: String?, excerpt: String? = "Paid in full") = CitationDto("chunk-1", sourceId, null, null, 0, 12, excerpt)

fun claim(vararg citations: CitationDto, basis: String = "evidence") = ClaimDto("The invoice was paid", basis, citations.toList())

fun turnDto(
    id: String,
    state: String = "pending",
    threadId: String = SERVER_THREAD,
    content: String = "Did I pay?",
    answer: String? = null,
    claims: List<ClaimDto> = emptyList(),
    reason: String? = null,
    errorCode: String? = null,
    invalidated: Boolean = false,
    withheld: Boolean = false,
    createdAt: String = "2026-10-08T10:00:00.000Z",
    completedAt: String? = null,
    scope: ScopeDto = ScopeDto("all"),
    /** Defaults to the emails the claims cite, which is what an answer with no uncited email looks like. */
    context: List<ContextDto>? = if (answer == null) null else claims.flatMap { c -> c.citations.mapNotNull { it.sourceId } }.distinct().map { ContextDto(it, "hybrid") },
    proposals: List<ProposalDto> = emptyList(),
    gaps: List<String> = emptyList(),
) = TurnDto(id, threadId, scope, content, state, reason, errorCode, answer, claims, gaps, proposals, context, invalidated, withheld, createdAt, completedAt)

fun fact(id: String, quarantined: Boolean = false, privacy: String = "hybrid", state: String = "ready") = SourceFact(id, quarantined, privacy, state)
