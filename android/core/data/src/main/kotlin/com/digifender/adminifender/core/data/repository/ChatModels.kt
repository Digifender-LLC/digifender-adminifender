package com.digifender.adminifender.core.data.repository

import com.digifender.adminifender.core.data.network.ChatScope

/** Whether the Worker has a thread for a local conversation. Creation never costs inference, so it is simply repeated. */
enum class CreateState {
    /** No request has been made yet. */
    NOT_CREATED,

    /** A request was made and its answer was not seen; the same key is sent again. A duplicate empty thread is the worst case. */
    UNCONFIRMED,

    CREATED,

    /** The Worker refused this scope for good (for example the email is quarantined or gone). */
    REFUSED,
}

/**
 * One conversation as stored on the phone. [localId] exists before the Worker has made a thread; [serverId] is the
 * Worker's UUID once creation is confirmed. [createKey] is the Idempotency-Key for creation, fixed for the life of the
 * row so a resend after a lost answer returns the same thread.
 */
data class ChatThread(
    val localId: String,
    val createKey: String,
    val serverId: String?,
    val scope: ChatScope,
    val createState: CreateState,
    val createError: String?,
    val draft: String,
    /**
     * Where the next page of earlier turns starts: the `nextBefore` the Worker gave with the oldest page read so far,
     * in its own order. Null when no earlier turns are known to exist. Never derived from the phone's clock or rows.
     */
    val olderCursor: String?,
    /** A history page has been read, so a later refresh of the newest page must not move [olderCursor] again. */
    val historyLoaded: Boolean,
    val createdAtMs: Long,
    val updatedAtMs: Long,
) {
    val olderAvailable: Boolean get() = olderCursor != null
}

/**
 * How far one question got. It is written before the step it names, so after a crash the row says what may have
 * happened: [ATTEMPTED] means the request may have reached the Worker.
 */
enum class Dispatch {
    /** Stored with its ID, exact text and scope; nothing has been sent. */
    QUEUED,

    /** The POST was started. The Worker may or may not have the turn; read it back before anything else. */
    ATTEMPTED,

    /** The Worker confirmed it has the turn. */
    ACCEPTED,

    /** The Worker refused it for good; nothing was stored there. */
    REFUSED,

    /** The owner cancelled it and the Worker had no record of it. This ID is never sent again. */
    WITHDRAWN,
}

enum class ServerState(val wire: String, val terminal: Boolean) {
    PENDING("pending", false),
    RUNNING("running", false),
    COMPLETE("complete", true),
    FAILED("failed", true),

    /** Dispatched and no result known: processing may still have happened and been charged. Never replayed. */
    UNCERTAIN("uncertain", false),
    CANCELLED("cancelled", true),
    ;

    companion object {
        fun fromWire(value: String): ServerState? = entries.firstOrNull { it.wire == value }
    }
}

enum class ClaimBasis(val wire: String) {
    EVIDENCE("evidence"),
    REPORT("report"),
    INFERENCE("inference"),
    HYPOTHESIS("hypothesis"),
    ;

    companion object {
        fun fromWire(value: String): ClaimBasis? = entries.firstOrNull { it.wire == value }
    }
}

/** A cited passage. [sourceId] is null only if the Worker could not name the email; such a citation cannot be opened. */
data class ChatCitation(val chunkId: String, val sourceId: String?, val attachmentId: String?, val page: Int?, val start: Int, val end: Int, val excerpt: String?)

data class ChatClaim(val text: String, val basis: ClaimBasis, val citations: List<ChatCitation>)

/** A read-only suggestion. It carries no authority to send or change anything; acting on one needs its own exact review. */
data class ChatProposal(val kind: String, val targetId: String, val description: String)

/**
 * An email the model was given, with the privacy it had then. The answer is derived from all of them, so all of them
 * (not only the cited ones) decide whether it may be shown.
 */
data class ContextSource(val sourceId: String, val privacy: String)

/** [context] is every email the answer rests on; it is stored with the answer so a later change can be matched exactly. */
data class ChatAnswer(val text: String, val claims: List<ChatClaim>, val gaps: List<String>, val proposals: List<ChatProposal>, val context: List<ContextSource>)

/** Why the phone does not show an answer it has read. */
enum class LocalInvalid {
    /** A synced email the answer rests on was deleted, quarantined or restricted after it was stored. The text is gone for good. */
    SOURCE_CHANGED,

    /** The emails it rests on are not all on the phone and eligible, or the Worker did not say what they are. A later read may show it. */
    UNVERIFIED,

    /** The answer broke the contract the app enforces (for example a claim stated as evidence with nothing cited). Never shown. */
    REJECTED,
}

/** Why a server answer could not be used at all. */
enum class AnswerProblem {
    /** The Worker sent an answer without the list of emails it rests on, so it cannot be checked. */
    CONTEXT_UNKNOWN,

    /** The answer broke the answer contract. */
    MALFORMED,
}

/** What the Worker said when the owner tried to cancel, kept so the screen keeps saying it. */
enum class CancelNote { ALREADY_DISPATCHED, NOT_CANCELLABLE }

/**
 * One question and what is known about its answer. [content] and [scope] are exactly what was submitted. [state] and
 * everything after it come from the Worker's durable record; the phone never invents them.
 */
data class ChatTurn(
    val turnId: String,
    val threadLocalId: String,
    val content: String,
    val scope: ChatScope,
    val dispatch: Dispatch,
    val dispatchError: String?,
    val state: ServerState?,
    val reason: String?,
    val errorCode: String?,
    val answer: ChatAnswer?,
    val serverInvalidated: Boolean,
    val serverWithheld: Boolean,
    val localInvalid: LocalInvalid?,
    val cancelNote: CancelNote?,
    val createdAtMs: Long,
    val completedAtMs: Long?,
    val checkedAtMs: Long?,
)

/** The Worker's view of a turn, after the wire shape has been checked. */
data class ServerTurn(
    val turnId: String,
    val serverThreadId: String,
    val scope: ChatScope,
    val content: String,
    val state: ServerState,
    val reason: String?,
    val errorCode: String?,
    val answer: ChatAnswer?,
    /** Set when the Worker sent an answer the phone cannot use; [answer] is then null. */
    val answerProblem: AnswerProblem?,
    val invalidated: Boolean,
    val withheld: Boolean,
    val createdAtMs: Long,
    val completedAtMs: Long?,
)

/** The distinct situations a turn can be in. The screen words each one differently; none stands in for another. */
enum class TurnPhase {
    /** Stored on the phone, not sent. */
    NOT_SENT,

    /** Sent, no answer seen: the Worker may or may not have it. */
    UNCONFIRMED,

    /** The Worker has it and has not started (possibly for a stated reason such as waiting for the phone). */
    WAITING,

    /** The Worker is producing the answer; cancelling can no longer undo that. */
    RUNNING,
    COMPLETE,
    FAILED,

    /** Dispatched, outcome unknown; processing may have happened. */
    UNCERTAIN,
    CANCELLED,

    /** The answer was removed because an email it rests on changed. */
    INVALIDATED,

    /** The Worker holds an answer back because an email it rests on no longer qualifies, or a cited passage can no longer be read. */
    WITHHELD,

    /** The phone holds an answer back until every email it rests on is on the phone and eligible. */
    UNVERIFIED,

    /** The answer broke the answer contract and is not shown. */
    ANSWER_REJECTED,

    /** The Worker refused to take the question. */
    REFUSED,

    /** Cancelled on the phone when the Worker had no record of it. A late arrival would show up as an ordinary question. */
    WITHDRAWN,
}

fun ChatTurn.phase(): TurnPhase = when {
    dispatch == Dispatch.REFUSED -> TurnPhase.REFUSED
    dispatch == Dispatch.QUEUED -> TurnPhase.NOT_SENT
    dispatch == Dispatch.ATTEMPTED -> TurnPhase.UNCONFIRMED
    dispatch == Dispatch.WITHDRAWN -> TurnPhase.WITHDRAWN
    localInvalid == LocalInvalid.SOURCE_CHANGED || serverInvalidated -> TurnPhase.INVALIDATED
    serverWithheld -> TurnPhase.WITHHELD
    localInvalid == LocalInvalid.REJECTED -> TurnPhase.ANSWER_REJECTED
    localInvalid == LocalInvalid.UNVERIFIED -> TurnPhase.UNVERIFIED
    else -> when (state) {
        null, ServerState.PENDING -> TurnPhase.WAITING
        ServerState.RUNNING -> TurnPhase.RUNNING
        ServerState.COMPLETE -> TurnPhase.COMPLETE
        ServerState.FAILED -> TurnPhase.FAILED
        ServerState.UNCERTAIN -> TurnPhase.UNCERTAIN
        ServerState.CANCELLED -> TurnPhase.CANCELLED
    }
}

/** Still moving: more reading or sending is worth doing. */
val ChatTurn.inFlight: Boolean
    get() = dispatch == Dispatch.QUEUED || dispatch == Dispatch.ATTEMPTED ||
        (dispatch == Dispatch.ACCEPTED && (state == null || state == ServerState.PENDING || state == ServerState.RUNNING))

/** Worth reading again on foreground or sync: in flight, or held back only until the emails it rests on are on the phone. */
val ChatTurn.needsCheck: Boolean get() = inFlight || localInvalid == LocalInvalid.UNVERIFIED

/** The owner may cancel while the Worker cannot yet have started: not sent, unknown, or waiting. */
val ChatTurn.cancellable: Boolean
    get() = when (phase()) {
        TurnPhase.NOT_SENT, TurnPhase.UNCONFIRMED -> true
        TurnPhase.WAITING -> true
        else -> false
    }

/** Poll waits after a send or a foreground, in milliseconds. After the last one only foreground or sync reads again. */
val CHAT_POLL_DELAYS_MS: List<Long> = listOf(1_000L, 2_000L, 5_000L, 10_000L)

/** The only suggestion the phone accepts in P1 (backend/src/chat/answer.ts): a reply to an email in context. */
val P1_PROPOSAL_KINDS: Set<String> = setOf("email.reply")

/** What the app holds that sign-out would discard without the owner having sent or finished it. */
data class ChatUnsent(
    /** Questions stored on the phone whose send the Worker has not confirmed (never sent, or sent with no answer seen). */
    val unconfirmedQuestions: Int,
    /** Conversations with unsent draft text. */
    val drafts: Int,
    /** Questions the Worker has and has not finished; they keep running there after sign-out unless a cancel gets through. */
    val atWorker: Int,
) {
    val any: Boolean get() = unconfirmedQuestions > 0 || drafts > 0 || atWorker > 0
}
