package com.digifender.adminifender.core.data.local

import androidx.room.withTransaction
import com.digifender.adminifender.core.data.network.ChatScope
import com.digifender.adminifender.core.data.network.ScopeKind
import com.digifender.adminifender.core.data.network.wireJson
import com.digifender.adminifender.core.data.repository.CancelNote
import com.digifender.adminifender.core.data.repository.ChatAnswer
import com.digifender.adminifender.core.data.repository.ChatCache
import com.digifender.adminifender.core.data.repository.ChatCitation
import com.digifender.adminifender.core.data.repository.ChatClaim
import com.digifender.adminifender.core.data.repository.ChatProposal
import com.digifender.adminifender.core.data.repository.ChatThread
import com.digifender.adminifender.core.data.repository.ChatTurn
import com.digifender.adminifender.core.data.repository.ChatUnsent
import com.digifender.adminifender.core.data.repository.ClaimBasis
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
import com.digifender.adminifender.core.data.repository.contextSourceIds
import com.digifender.adminifender.core.data.repository.inFlight
import com.digifender.adminifender.core.data.repository.planPage
import com.digifender.adminifender.core.data.repository.staleAnswers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

@Serializable
private data class StoredCitation(val chunkId: String, val sourceId: String? = null, val attachmentId: String? = null, val page: Int? = null, val start: Int, val end: Int, val excerpt: String? = null)

@Serializable
private data class StoredClaim(val text: String, val basis: String, val citations: List<StoredCitation> = emptyList())

@Serializable
private data class StoredProposal(val kind: String, val targetId: String, val description: String)

@Serializable
private data class StoredContext(val sourceId: String, val privacy: String)

/**
 * `context` has no default on purpose: an answer stored without the emails it rests on cannot be matched to a later
 * restriction, so it fails to decode, is treated as damaged and is read again from the Worker.
 */
@Serializable
private data class StoredAnswer(
    val text: String,
    val claims: List<StoredClaim> = emptyList(),
    val gaps: List<String> = emptyList(),
    val proposals: List<StoredProposal> = emptyList(),
    val context: List<StoredContext>,
)

/** The answer as stored in `chat_turns.answerJson`. Throws IllegalArgumentException on a damaged value. */
internal fun encodeAnswer(answer: ChatAnswer): String = wireJson.encodeToString(
    StoredAnswer.serializer(),
    StoredAnswer(
        answer.text,
        answer.claims.map { claim -> StoredClaim(claim.text, claim.basis.wire, claim.citations.map { StoredCitation(it.chunkId, it.sourceId, it.attachmentId, it.page, it.start, it.end, it.excerpt) }) },
        answer.gaps,
        answer.proposals.map { StoredProposal(it.kind, it.targetId, it.description) },
        answer.context.map { StoredContext(it.sourceId, it.privacy) },
    ),
)

internal fun decodeAnswer(text: String): ChatAnswer {
    val stored = wireJson.decodeFromString(StoredAnswer.serializer(), text)
    return ChatAnswer(
        stored.text,
        stored.claims.map { claim ->
            ChatClaim(
                claim.text,
                requireNotNull(ClaimBasis.fromWire(claim.basis)) { "claim_basis" },
                claim.citations.map { ChatCitation(it.chunkId, it.sourceId, it.attachmentId, it.page, it.start, it.end, it.excerpt) },
            )
        },
        stored.gaps,
        stored.proposals.map { ChatProposal(it.kind, it.targetId, it.description) },
        stored.context.map { ContextSource(it.sourceId, it.privacy) },
    )
}

private fun ChatThreadEntity.toThread() = ChatThread(
    localId = localId,
    createKey = createKey,
    serverId = serverId,
    scope = ChatScope(ScopeKind.fromWire(scopeKind) ?: ScopeKind.ALL, scopeId),
    createState = CreateState.entries.firstOrNull { it.name == createState } ?: CreateState.UNCONFIRMED,
    createError = createError,
    draft = draft,
    olderCursor = olderCursor,
    historyLoaded = historyLoaded,
    createdAtMs = createdAt,
    updatedAtMs = updatedAt,
)

private fun ChatThread.toEntity() = ChatThreadEntity(
    localId = localId,
    createKey = createKey,
    serverId = serverId,
    scopeKind = scope.kind.wire,
    scopeId = scope.id,
    createState = createState.name,
    createError = createError,
    draft = draft,
    olderCursor = olderCursor,
    historyLoaded = historyLoaded,
    createdAt = createdAtMs,
    updatedAt = updatedAtMs,
)

private fun ChatTurnEntity.toTurn(): ChatTurn {
    // A damaged answer is treated as held back, so the next read of the turn can restore it from the Worker.
    val decoded = answerJson?.let { runCatching { decodeAnswer(it) }.getOrNull() }
    val damaged = answerJson != null && decoded == null
    val invalid = localInvalid?.let { name -> LocalInvalid.entries.firstOrNull { it.name == name } }
    return ChatTurn(
        turnId = turnId,
        threadLocalId = threadLocalId,
        content = content,
        scope = ChatScope(ScopeKind.fromWire(scopeKind) ?: ScopeKind.ALL, scopeId),
        // An unreadable dispatch value is the most cautious one: the request may have been made.
        dispatch = Dispatch.entries.firstOrNull { it.name == dispatch } ?: Dispatch.ATTEMPTED,
        dispatchError = dispatchError,
        state = state?.let { name -> ServerState.entries.firstOrNull { it.name == name } },
        reason = reason,
        errorCode = errorCode,
        answer = decoded,
        serverInvalidated = serverInvalidated,
        serverWithheld = serverWithheld,
        localInvalid = invalid ?: if (damaged) LocalInvalid.UNVERIFIED else null,
        cancelNote = cancelNote?.let { name -> CancelNote.entries.firstOrNull { it.name == name } },
        createdAtMs = createdAt,
        completedAtMs = completedAt,
        checkedAtMs = checkedAt,
    )
}

private fun ChatTurn.toEntity() = ChatTurnEntity(
    turnId = turnId,
    threadLocalId = threadLocalId,
    content = content,
    scopeKind = scope.kind.wire,
    scopeId = scope.id,
    dispatch = dispatch.name,
    dispatchError = dispatchError,
    state = state?.name,
    reason = reason,
    errorCode = errorCode,
    answerJson = answer?.let(::encodeAnswer),
    serverInvalidated = serverInvalidated,
    serverWithheld = serverWithheld,
    localInvalid = localInvalid?.name,
    cancelNote = cancelNote?.name,
    createdAt = createdAtMs,
    completedAt = completedAtMs,
    checkedAt = checkedAtMs,
)

internal fun SourceEntity.toFact() = SourceFact(id, quarantined, privacy, state)

/** Room-backed [ChatCache]. Every update is conditional on the row still existing, so nothing is resurrected after sign-out. */
class RoomChatCache(private val db: AppDatabase) : ChatCache {
    private val dao get() = db.chat()

    override fun threads() = dao.observeThreads().map { rows -> rows.map { it.toThread() } }

    override fun thread(localId: String) = dao.observeThread(localId).map { it?.toThread() }

    override fun turns(localId: String) = dao.observeTurns(localId).map { rows -> rows.map { it.toTurn() } }

    override suspend fun threadOnce(localId: String) = dao.thread(localId)?.toThread()

    override suspend fun latestThread(scope: ChatScope) = dao.latest(scope.kind.wire, scope.id)?.toThread()

    override suspend fun turnOnce(turnId: String) = dao.turn(turnId)?.toTurn()

    override suspend fun turnsOnce(localId: String) = dao.turns(localId).map { it.toTurn() }

    override suspend fun needingCheck(): List<ChatTurn> {
        holdBackUnreadable()
        return dao.needingCheck().map { it.toTurn() }
    }

    private val scan = Mutex()
    private var scanned = false

    /** How many stored answers the first-load check has decoded; a test can see that polling does not decode again. */
    internal var answersChecked = 0
        private set

    /**
     * A stored answer that no longer decodes (damage, or a format from an older build) cannot be matched to the emails it
     * rests on. Every answer this build writes decodes, and nothing else writes them, so all such rows exist when the
     * process starts: they are found by one pass over the stored answers, the first time anything asks, and held back as
     * persisted state (text removed, turn marked unverified), which the cheap query in [needingCheck] finds from then
     * on and a read of the turn replaces. Polling, which asks every few seconds, never decodes stored answers again.
     */
    private suspend fun holdBackUnreadable() {
        if (scanned) return
        scan.withLock {
            if (scanned) return
            db.withTransaction {
                val unreadable = dao.answered().filter {
                    answersChecked++
                    runCatching { decodeAnswer(requireNotNull(it.answerJson)) }.isFailure
                }.map { it.turnId }
                unreadable.chunked(500).forEach { dao.holdBack(it) }
            }
            scanned = true
        }
    }

    override suspend fun insertThread(thread: ChatThread) = dao.insertThread(thread.toEntity())

    override suspend fun setDraft(localId: String, draft: String) = dao.setDraft(localId, draft) > 0

    override suspend fun updateCreate(localId: String, state: CreateState, serverId: String?, error: String?) = dao.setCreate(localId, state.name, serverId, error) > 0

    override suspend fun submit(turn: ChatTurn, nowMs: Long): Boolean = db.withTransaction {
        if (dao.thread(turn.threadLocalId) == null) return@withTransaction false
        dao.insertTurn(turn.toEntity())
        dao.setDraft(turn.threadLocalId, "")
        dao.touch(turn.threadLocalId, nowMs)
        true
    }

    override suspend fun markAttempted(turnId: String) = dao.setDispatch(turnId, Dispatch.ATTEMPTED.name, null, listOf(Dispatch.QUEUED.name)) > 0

    override suspend fun setDispatch(turnId: String, dispatch: Dispatch, error: String?, from: Set<Dispatch>) =
        dao.setDispatch(turnId, dispatch.name, error, from.map { it.name }) > 0

    override suspend fun accepted(turnId: String, state: ServerState?, nowMs: Long): Boolean = db.withTransaction {
        val row = dao.turn(turnId)?.toTurn() ?: return@withTransaction false
        if (row.dispatch != Dispatch.QUEUED && row.dispatch != Dispatch.ATTEMPTED) return@withTransaction false
        dao.upsertTurns(listOf(row.copy(dispatch = Dispatch.ACCEPTED, dispatchError = null, state = row.state ?: state, checkedAtMs = nowMs).toEntity()))
        true
    }

    override suspend fun setCancelNote(turnId: String, note: CancelNote) = dao.setCancelNote(turnId, note.name) > 0

    override suspend fun editAgain(turnId: String): EditAgain = db.withTransaction {
        val row = dao.turn(turnId)?.toTurn() ?: return@withTransaction EditAgain.Unavailable
        if (row.dispatch != Dispatch.REFUSED && row.dispatch != Dispatch.WITHDRAWN) return@withTransaction EditAgain.Unavailable
        val thread = dao.thread(row.threadLocalId)?.toThread() ?: return@withTransaction EditAgain.Unavailable
        if (thread.draft.isNotBlank() && thread.draft != row.content) return@withTransaction EditAgain.DraftInUse
        // The text is in the draft before the question row goes, in the same transaction.
        dao.setDraft(thread.localId, row.content)
        dao.deleteTurn(turnId)
        EditAgain.Restored(row.content)
    }

    override suspend fun deleteThread(localId: String): DeleteThread = db.withTransaction {
        if (dao.thread(localId) == null) return@withTransaction DeleteThread.Unavailable
        if (dao.turns(localId).any { it.toTurn().inFlight() }) return@withTransaction DeleteThread.InFlight
        dao.deleteThread(localId)
        DeleteThread.Deleted
    }

    override fun unsent(): Flow<ChatUnsent> = combine(dao.observeUnconfirmed(), dao.observeDrafts(), dao.observeAtWorker()) { unconfirmed, drafts, atWorker -> ChatUnsent(unconfirmed, drafts, atWorker) }

    override suspend fun applyServer(threadLocalId: String, page: List<ServerTurn>, kind: PageKind, insertUnknown: Boolean, nowMs: Long): Boolean = db.withTransaction {
        val thread = dao.thread(threadLocalId)?.toThread() ?: return@withTransaction false
        val existing = dao.turnsById(page.map { it.turnId }).map { it.toTurn() }.associateBy { it.turnId }
        // The cited emails are read in this transaction, so an answer is never judged against a cache older than its write.
        val facts = factsFor(contextSourceIds(page))
        val plan = planPage(thread, existing, page, kind, insertUnknown, facts, nowMs)
        if (plan.upserts.isNotEmpty()) dao.upsertTurns(plan.upserts.map { it.toEntity() })
        if (plan.olderCursor != thread.olderCursor || plan.historyLoaded != thread.historyLoaded) dao.setHistory(threadLocalId, plan.olderCursor, plan.historyLoaded)
        true
    }

    override suspend fun dropRestrictedAnswers(changed: Set<String>): Int = db.withTransaction {
        if (changed.isEmpty()) return@withTransaction 0
        val answered = dao.answered()
        // Stored text that can no longer be read cannot be matched to the emails it rests on, so on any restriction it
        // is removed and the turn is held back to be read again; it is never kept on the chance that it is unaffected.
        val readable = answered.mapNotNull { row -> runCatching { decodeAnswer(requireNotNull(row.answerJson)) }.getOrNull()?.let { row to it } }
        val readableIds = readable.map { it.first.turnId }.toSet()
        answered.map { it.turnId }.filter { it !in readableIds }.chunked(500).forEach { dao.holdBack(it) }
        val resting = readable.filter { (_, answer) -> answer.context.any { it.sourceId in changed } }.map { it.first.toTurn() }
        val facts = factsFor(resting.flatMap { turn -> turn.answer?.context.orEmpty().map { it.sourceId } })
        val ids = staleAnswers(changed, resting, facts)
        ids.chunked(500).forEach { dao.invalidate(it) }
        ids.size
    }

    /** The cache's current record of these emails; one that is absent is not on the phone. */
    private suspend fun factsFor(ids: List<String>): Map<String, SourceFact> =
        ids.distinct().chunked(500).flatMap { db.sources().getMany(it) }.associate { it.id to it.toFact() }
}
