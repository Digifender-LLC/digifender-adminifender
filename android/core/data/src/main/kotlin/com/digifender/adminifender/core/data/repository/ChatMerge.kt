package com.digifender.adminifender.core.data.repository

import com.digifender.adminifender.core.data.network.ChatScope
import com.digifender.adminifender.core.data.network.CitationDto
import com.digifender.adminifender.core.data.network.ScopeDto
import com.digifender.adminifender.core.data.network.ScopeKind
import com.digifender.adminifender.core.data.network.TurnDto
import com.digifender.adminifender.core.data.network.isRecordId
import java.time.Instant

private fun ScopeDto.toScope(): ChatScope {
    val parsed = requireNotNull(ScopeKind.fromWire(kind)) { "scope_kind" }
    return ChatScope(parsed, id).also { require(it.wellFormed) { "scope_shape" } }
}

private val CHUNK_ID = Regex("^[A-Za-z0-9:_.-]{1,200}$")

// The answer contract the Worker enforces on a model (backend/src/chat/answer.ts, chat-answer.schema.json) and the
// turn schema it publishes (chat-turn.schema.json). The phone checks it again: defence in depth, not trust.
private const val MAX_ANSWER_CHARS = 4000
private const val MAX_CLAIMS = 10
private const val MAX_CLAIM_CHARS = 1000
private const val MAX_CITATIONS = 4
private const val MAX_OFFSET = 6000
private const val MAX_GAPS = 5
private const val MAX_GAP_CHARS = 300
private const val MAX_PROPOSALS = 3
private const val MAX_PROPOSAL_CHARS = 500
private const val MAX_CONTEXT_SOURCES = 4

private sealed interface AnswerParse {
    data class Ok(val answer: ChatAnswer) : AnswerParse

    data class Unusable(val problem: AnswerProblem) : AnswerParse
}

private fun CitationDto.toCitation(): ChatCitation? {
    if (!CHUNK_ID.matches(chunkId)) return null
    if (sourceId == null || !isRecordId(sourceId)) return null
    if (attachmentId != null && !isRecordId(attachmentId)) return null
    if (page != null && page < 0) return null
    if (start !in 0..MAX_OFFSET || end !in 1..MAX_OFFSET || start >= end) return null
    if (excerpt != null && excerpt.length > MAX_OFFSET) return null
    return ChatCitation(chunkId, sourceId, attachmentId, page, start, end, excerpt)
}

/**
 * Reduces the answer part of a turn to what the phone keeps, or says why it cannot. An unknown claim basis throws (a
 * newer Worker is not half understood); every other break of the contract makes the answer [AnswerProblem.MALFORMED]
 * without costing the rest of the page. Citations and proposal targets must name emails in the context, and
 * `evidence` and `report` claims must cite something.
 */
private fun TurnDto.parseAnswer(text: String): AnswerParse {
    val bases = claims.map { requireNotNull(ClaimBasis.fromWire(it.basis)) { "claim_basis" } }
    val unusable = AnswerParse.Unusable(AnswerProblem.MALFORMED)
    if (text.isEmpty() || text.length > MAX_ANSWER_CHARS) return unusable
    if (claims.size > MAX_CLAIMS || gaps.size > MAX_GAPS || proposals.size > MAX_PROPOSALS) return unusable
    if (gaps.any { it.isEmpty() || it.length > MAX_GAP_CHARS }) return unusable
    val parsedClaims = claims.mapIndexed { index, claim ->
        if (claim.text.isEmpty() || claim.text.length > MAX_CLAIM_CHARS || claim.citations.size > MAX_CITATIONS) return unusable
        val basis = bases[index]
        if ((basis == ClaimBasis.EVIDENCE || basis == ClaimBasis.REPORT) && claim.citations.isEmpty()) return unusable
        val citations = claim.citations.map { it.toCitation() ?: return unusable }
        ChatClaim(claim.text, basis, citations)
    }
    val parsedProposals = proposals.map { proposal ->
        if (proposal.kind !in P1_PROPOSAL_KINDS || !isRecordId(proposal.targetId)) return unusable
        if (proposal.description.isEmpty() || proposal.description.length > MAX_PROPOSAL_CHARS) return unusable
        ChatProposal(proposal.kind, proposal.targetId, proposal.description)
    }
    // Without the emails it rests on the answer cannot be checked at all.
    val wireContext = context ?: return AnswerParse.Unusable(AnswerProblem.CONTEXT_UNKNOWN)
    if (wireContext.size > MAX_CONTEXT_SOURCES) return unusable
    val parsedContext = wireContext.map { source ->
        if (!isRecordId(source.sourceId) || source.privacy !in RECORDED_PRIVACY) return unusable
        ContextSource(source.sourceId, source.privacy)
    }
    val known = parsedContext.map { it.sourceId }.toSet()
    if (parsedClaims.any { claim -> claim.citations.any { it.sourceId !in known } }) return unusable
    if (parsedProposals.any { it.targetId !in known }) return unusable
    return AnswerParse.Ok(ChatAnswer(text, parsedClaims, gaps, parsedProposals, parsedContext))
}

/**
 * Checks a turn from the wire and reduces it to what the phone keeps. Throws IllegalArgumentException or
 * DateTimeParseException for anything the phone cannot interpret exactly: an unknown state or basis is an error, not
 * something to skip, so a newer Worker is never half understood. An answer that breaks the answer contract does not
 * throw: it is carried as [ServerTurn.answerProblem] so one bad answer cannot hide the rest of a history page.
 */
fun TurnDto.toServerTurn(): ServerTurn {
    require(isRecordId(turnId)) { "turn_id" }
    require(isRecordId(threadId)) { "thread_id" }
    val parsedState = requireNotNull(ServerState.fromWire(state)) { "turn_state" }
    val parsed = answer?.let { parseAnswer(it) }
    return ServerTurn(
        turnId = turnId,
        serverThreadId = threadId,
        scope = scope.toScope(),
        content = content,
        state = parsedState,
        reason = reason,
        errorCode = errorCode,
        answer = (parsed as? AnswerParse.Ok)?.answer,
        answerProblem = (parsed as? AnswerParse.Unusable)?.problem,
        invalidated = invalidated,
        withheld = withheld,
        createdAtMs = Instant.parse(createdAt).toEpochMilli(),
        completedAtMs = completedAt?.let { Instant.parse(it).toEpochMilli() },
    )
}

/** Every email the answers on a server turn page rest on, for the cache to look up before it merges. */
fun contextSourceIds(page: List<ServerTurn>): List<String> = page.flatMap { turn -> turn.answer?.context.orEmpty().map { it.sourceId } }.distinct()

/**
 * Applies the Worker's record of a turn to what the phone holds. The rules:
 * - the exact text and scope the owner submitted are kept; the Worker's copy only fills in a turn the phone lacks;
 * - a finished turn (complete, failed, cancelled) never goes back to waiting, so an older response cannot undo a newer one;
 * - an answer once removed for [LocalInvalid.SOURCE_CHANGED] stays removed whatever the Worker now says;
 * - an answer is stored only if every email it rests on (its whole context, not just the cited ones) is on the phone,
 *   eligible and no stricter than when it was made. Otherwise it is held back as [LocalInvalid.UNVERIFIED] and a later
 *   read decides again; one that breaks the answer contract is [LocalInvalid.REJECTED] and never stored.
 */
fun mergeServerTurn(local: ChatTurn?, server: ServerTurn, threadLocalId: String, facts: Map<String, SourceFact>, nowMs: Long): ChatTurn {
    val base = local ?: ChatTurn(
        turnId = server.turnId,
        threadLocalId = threadLocalId,
        content = server.content,
        scope = server.scope,
        dispatch = Dispatch.ACCEPTED,
        dispatchError = null,
        state = null,
        reason = null,
        errorCode = null,
        answer = null,
        serverInvalidated = false,
        serverWithheld = false,
        localInvalid = null,
        cancelNote = null,
        createdAtMs = server.createdAtMs,
        completedAtMs = null,
        checkedAtMs = null,
    )
    if (base.state?.terminal == true && !server.state.terminal) return base.copy(dispatch = Dispatch.ACCEPTED, checkedAtMs = nowMs)
    val sticky = base.localInvalid == LocalInvalid.SOURCE_CHANGED
    val serverAnswer = server.answer
    val shown = !sticky && serverAnswer != null && checkContext(serverAnswer.context, facts) == ContextCheck.HOLDS
    return base.copy(
        dispatch = Dispatch.ACCEPTED,
        dispatchError = null,
        state = server.state,
        reason = server.reason,
        errorCode = server.errorCode,
        answer = if (shown) serverAnswer else null,
        serverInvalidated = server.invalidated,
        serverWithheld = server.withheld,
        localInvalid = when {
            sticky -> LocalInvalid.SOURCE_CHANGED
            server.answerProblem == AnswerProblem.MALFORMED -> LocalInvalid.REJECTED
            server.answerProblem == AnswerProblem.CONTEXT_UNKNOWN -> LocalInvalid.UNVERIFIED
            serverAnswer != null && !shown -> LocalInvalid.UNVERIFIED
            else -> null
        },
        completedAtMs = server.completedAtMs,
        checkedAtMs = nowMs,
    )
}

/**
 * Which part of the history a page of server turns is, and what the Worker said about the turns before it. The Worker
 * orders history by its own record, so the only cursor the phone keeps is the `nextBefore` it was given, never a
 * position worked out from the phone's clock or from the order of the phone's own rows.
 */
sealed interface PageKind {
    /** A single turn read: the history cursor is not touched. */
    data object Single : PageKind

    /**
     * The newest page. It sets the cursor only while no history page has been read; once one has, the cursor already
     * points at or beyond this page's oldest turn and the refresh never moves it back to a newer position.
     */
    data class Latest(val nextBefore: String?) : PageKind

    /** The page asked for with `before`; its own [nextBefore] (null at the start of the history) replaces the cursor. */
    data class Earlier(val before: String, val nextBefore: String?) : PageKind
}

data class PagePlan(val upserts: List<ChatTurn>, val olderCursor: String?, val historyLoaded: Boolean)

/**
 * What storing a page of server turns for [thread] changes. [existing] holds the phone's rows for those turn IDs, in
 * any thread. A row that belongs to another conversation, or whose stored text or scope differs from what the Worker
 * echoes, is never touched. With [insertUnknown] false (a single turn
 * read for a turn the phone started) a turn the phone does not hold is not created, so a late response cannot bring
 * back a turn that was dropped.
 */
fun planPage(
    thread: ChatThread,
    existing: Map<String, ChatTurn>,
    page: List<ServerTurn>,
    kind: PageKind,
    insertUnknown: Boolean,
    facts: Map<String, SourceFact>,
    nowMs: Long,
): PagePlan {
    val upserts = page.mapNotNull { server ->
        val local = existing[server.turnId]
        when {
            local != null && local.threadLocalId != thread.localId -> null
            // An answer about other text or another scope is not about this question, whatever its ID says.
            local != null && (local.content != server.content || local.scope != server.scope) -> null
            local == null && !insertUnknown -> null
            else -> mergeServerTurn(local, server, thread.localId, facts, nowMs)
        }
    }
    val (cursor, loaded) = when (kind) {
        PageKind.Single -> thread.olderCursor to thread.historyLoaded
        is PageKind.Latest -> if (thread.historyLoaded) thread.olderCursor to true else kind.nextBefore to true
        // Only the page that was asked for at the current cursor may move it; any other answer cannot.
        is PageKind.Earlier -> if (thread.olderCursor == kind.before) kind.nextBefore to true else thread.olderCursor to thread.historyLoaded
    }
    return PagePlan(upserts, cursor, loaded)
}
