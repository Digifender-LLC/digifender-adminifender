package com.digifender.adminifender.core.data.review

import com.digifender.adminifender.core.data.network.Cleanup
import com.digifender.adminifender.core.data.network.ConfirmResult
import com.digifender.adminifender.core.data.network.DeletionPreviewSource
import com.digifender.adminifender.core.data.network.MAX_DELETE_SOURCES
import com.digifender.adminifender.core.data.network.Operation
import com.digifender.adminifender.core.data.network.OperationReceipt
import com.digifender.adminifender.core.data.network.OperationState
import com.digifender.adminifender.core.data.network.PrepareResult
import com.digifender.adminifender.core.data.network.PreparedDeletion
import com.digifender.adminifender.core.data.network.PreparedReview
import com.digifender.adminifender.core.data.network.ReviewApi
import com.digifender.adminifender.core.data.network.ReviewEffect
import com.digifender.adminifender.core.data.network.ReviewRead
import com.digifender.adminifender.core.data.network.ReviewReadback
import com.digifender.adminifender.core.data.network.ReviewState
import com.digifender.adminifender.core.data.network.isUuid
import com.digifender.adminifender.core.data.repository.ArchiveCache
import com.digifender.adminifender.core.data.session.PasskeyAuthenticator
import com.digifender.adminifender.core.data.session.RecentAuthOutcome
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Proves the owner is present with a fresh passkey assertion (the session manager in the app). */
fun interface RecentAuthenticator {
    suspend fun confirm(passkeys: PasskeyAuthenticator): RecentAuthOutcome
}

sealed interface PrepareOutcome {
    /** The Worker's exact review is stored; [subjects] are what it read, shown now and never kept. */
    data class Ready(val review: DeletionReview, val subjects: List<DeletionPreviewSource>) : PrepareOutcome

    /** The request is stored but no answer came. Trying again sends the same key and replays the same review. */
    data class Waiting(val review: DeletionReview, val code: String) : PrepareOutcome

    data object Empty : PrepareOutcome

    data class TooMany(val limit: Int) : PrepareOutcome

    /** Emails the phone does not hold cannot be reviewed from here. */
    data class NotOnPhone(val missing: List<String>) : PrepareOutcome

    data object SignIn : PrepareOutcome

    /** The selection changed or the review was dropped while the request was out; its answer was not stored. */
    data object Superseded : PrepareOutcome

    data class Refused(val review: DeletionReview, val code: String) : PrepareOutcome

    /** The Worker's review does not describe the selection that was asked for; nothing is offered for confirmation. */
    data class Mismatch(val review: DeletionReview) : PrepareOutcome
}

sealed interface ConfirmOutcome {
    data class Deleted(val review: DeletionReview) : ConfirmOutcome

    /** The owner dismissed the passkey prompt. Nothing was sent. */
    data object AuthCancelled : ConfirmOutcome

    data class AuthFailed(val code: String) : ConfirmOutcome

    data object SignIn : ConfirmOutcome

    /** The review ran out; a new review is a new explicit action. */
    data object Expired : ConfirmOutcome

    /** An email in the review changed on this phone after it was prepared. */
    data object Changed : ConfirmOutcome

    /** The Worker said it deleted nothing. */
    data class Rejected(val review: DeletionReview) : ConfirmOutcome

    /** The Worker said nothing was committed and the same confirmation may be repeated. */
    data class NotCompleted(val review: DeletionReview, val code: String) : ConfirmOutcome

    /** The confirmation was sent and no answer is known. Only a read-back can settle it; nothing is sent again by itself. */
    data class Unknown(val review: DeletionReview, val code: String) : ConfirmOutcome

    data class NotAllowed(val phase: DeletionPhase?) : ConfirmOutcome

    /** The review was dropped, replaced, settled or changed while the passkey prompt was open. Nothing was sent. */
    data object Superseded : ConfirmOutcome
}

sealed interface ReadbackOutcome {
    /** Settled: the Worker's operation shows the deletion happened. */
    data class Deleted(val review: DeletionReview) : ReadbackOutcome

    /** Settled: the review expired without being used, as far as the Worker could tell. */
    data class Unused(val review: DeletionReview) : ReadbackOutcome

    /** Still not settled. [review] carries what was read. */
    data class Unsettled(val review: DeletionReview, val reason: UnsettledReason) : ReadbackOutcome

    data object SignIn : ReadbackOutcome

    data object NothingToRead : ReadbackOutcome
}

enum class UnsettledReason {
    /** Not consumed when read; the confirmation could still arrive or be mid-flight. */
    OPEN,

    /** Expired when read, but too recently to rule out a confirmation that was in flight. */
    EXPIRED_RECENTLY,

    /** The operation exists but has not settled as succeeded (or it is unreadable). */
    OPERATION_UNSETTLED,

    /** 404 or 410: not known to the Worker, which is never evidence that nothing happened. */
    NOT_KNOWN,

    UNAVAILABLE,

    /** The read-back described another review than this one. */
    MISMATCH,
}

/** Counts for the places that must name unfinished deletions: sign-out and the storage screen. */
data class DeletionPending(val unknown: Int, val open: Int)

/** Review is advisory on the phone's clock; the Worker decides at confirmation. */
private const val EXPIRY_GRACE_MS = 60_000L
private const val TERMINAL_KEEP_MS = 90L * 24 * 60 * 60 * 1000

private val OPEN_PHASES = setOf(DeletionPhase.PREPARING, DeletionPhase.PREPARED, DeletionPhase.CONFIRMING)
private val UNSETTLED_PHASES = setOf(DeletionPhase.CONFIRMING, DeletionPhase.SENDING)
private val TERMINAL_PHASES = setOf(DeletionPhase.SUCCEEDED, DeletionPhase.REJECTED, DeletionPhase.STOPPED)

/**
 * Archive deletion as an exact reviewed operation (FR-011, lld.md#reviews-external-effects-and-readback). The phone
 * stores its intent before every request. A confirmation that gets no answer stays unknown: it is read back, never
 * redispatched, and a replacement review is always a new explicit action by the owner.
 *
 * Every answer, prompt result and read-back is applied through [mutate]: under one lock, against the row as it is
 * *now*, only for the phases that answer may move, and only while the sign-in epoch it started in is current. A row
 * that was dropped, replaced or already settled is never recreated or overwritten by a late response, and the
 * terminal phases never change.
 */
class DeletionRepository(
    private val api: ReviewApi,
    private val store: DeletionStore,
    private val archive: ArchiveCache,
    private val purge: DeletedSourcePurge,
    private val recent: RecentAuthenticator,
    private val clock: () -> Long,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    /** Serializes changes to rows; it is never held across a request or a passkey prompt. */
    private val rows = Mutex()
    private val inFlight = mutableSetOf<String>()
    private var epoch = 0

    /** Subjects the Worker returned, for this process only. */
    private val subjects = mutableMapOf<String, List<DeletionPreviewSource>>()

    /** Sign-out: answers that arrive from now on, for requests that started before it, are discarded. */
    suspend fun invalidate() {
        rows.withLock {
            epoch += 1
            subjects.clear()
            inFlight.clear()
        }
    }

    fun reviews(): Flow<List<DeletionReview>> = store.observeAll()

    fun review(localId: String): Flow<DeletionReview?> = store.observe(localId)

    fun pending(): Flow<DeletionPending> = store.observeAll().map { all ->
        DeletionPending(
            unknown = all.count { it.phase == DeletionPhase.SENDING },
            open = all.count { it.phase in OPEN_PHASES },
        )
    }

    /** The subjects the Worker read for [localId] in this process, if the review was prepared here. */
    suspend fun serverSubjects(localId: String): List<DeletionPreviewSource> = rows.withLock { subjects[localId].orEmpty() }

    private suspend fun epochNow() = rows.withLock { epoch }

    /**
     * The review already on the phone for exactly [selected], if one is still open or has an unknown outcome. A screen that is
     * opened again adopts it instead of preparing a new one, so coming back never starts a second review by itself.
     */
    suspend fun active(selected: Collection<String>): DeletionReview? {
        val ids = selected.distinct().sorted()
        return store.all().filter { it.sourceIds == ids && (it.phase in OPEN_PHASES || it.phase == DeletionPhase.SENDING) }.maxByOrNull { it.createdAtMs }
    }

    /** Applies [change] to the current row if it exists, is in one of [allowed] and the epoch is still [startedIn]. Null means nothing was written. */
    private suspend fun mutate(localId: String, startedIn: Int, allowed: Set<DeletionPhase>, change: suspend (DeletionReview) -> DeletionReview?): DeletionReview? = rows.withLock {
        if (epoch != startedIn) return@withLock null
        val current = store.get(localId) ?: return@withLock null
        if (current.phase !in allowed) return@withLock null
        val next = change(current) ?: return@withLock null
        store.put(next)
        next
    }

    /**
     * Starts the review of exactly [selected]. A review for any other selection that was not yet sent as a confirmation
     * is dropped (changed selection invalidates a review); one for the same selection is reused unless it ran out.
     */
    suspend fun prepare(selected: Collection<String>): PrepareOutcome {
        val ids = selected.distinct().sorted()
        if (ids.isEmpty()) return PrepareOutcome.Empty
        if (ids.size > MAX_DELETE_SOURCES) return PrepareOutcome.TooMany(MAX_DELETE_SOURCES)
        val held = archive.sources(ids).first().map { it.id }.toSet()
        val missing = ids.filter { it !in held }
        if (missing.isNotEmpty()) return PrepareOutcome.NotOnPhone(missing)
        val (row, startedIn) = rows.withLock {
            val now = clock()
            var reuse: DeletionReview? = null
            for (existing in store.all()) {
                if (existing.phase !in OPEN_PHASES) continue
                if (existing.sourceIds == ids && reuse == null && !isExpired(existing, now)) {
                    reuse = existing
                } else {
                    // Only rows nothing was confirmed from are dropped; a confirming one that is mid-prompt finds itself gone and sends nothing.
                    store.delete(existing.localId)
                    subjects.remove(existing.localId)
                }
            }
            val made = reuse ?: DeletionReview(
                localId = newId(),
                sourceIds = ids,
                prepareKey = newId(),
                phase = DeletionPhase.PREPARING,
                errorCode = null,
                reviewId = null,
                paramsDigest = null,
                expiresAtMs = null,
                sourceRevisions = emptyMap(),
                concernRevisions = emptyMap(),
                preview = null,
                confirmKey = null,
                operationId = null,
                receipt = null,
                cleanup = null,
                cleanupCheckedAtMs = null,
                readback = null,
                createdAtMs = now,
                updatedAtMs = now,
            ).also { store.put(it) }
            made to epoch
        }
        return when (row.phase) {
            DeletionPhase.PREPARING -> send(row, startedIn)
            else -> PrepareOutcome.Ready(row, rows.withLock { subjects[row.localId].orEmpty() })
        }
    }

    /** Sends the stored preparation again with its own key. The owner asks for it; it never runs by itself. */
    suspend fun retryPrepare(localId: String): PrepareOutcome {
        val startedIn = epochNow()
        val row = store.get(localId)?.takeIf { it.phase == DeletionPhase.PREPARING } ?: return PrepareOutcome.Empty
        return send(row, startedIn)
    }

    private suspend fun send(row: DeletionReview, startedIn: Int): PrepareOutcome {
        val result = api.prepareDeletion(row.prepareKey, row.sourceIds)
        if (result == PrepareResult.SessionExpired) return PrepareOutcome.SignIn
        return when (result) {
            PrepareResult.SessionExpired -> PrepareOutcome.SignIn
            is PrepareResult.NoAnswer -> mutate(row.localId, startedIn, setOf(DeletionPhase.PREPARING)) { it.copy(errorCode = result.code, updatedAtMs = clock()) }
                ?.let { PrepareOutcome.Waiting(it, result.code) } ?: PrepareOutcome.Superseded
            is PrepareResult.Refused -> mutate(row.localId, startedIn, setOf(DeletionPhase.PREPARING)) { it.copy(phase = DeletionPhase.REJECTED, errorCode = result.code, updatedAtMs = clock()) }
                ?.let { PrepareOutcome.Refused(it, result.code) } ?: PrepareOutcome.Superseded
            is PrepareResult.Prepared -> {
                val prepared = (result.review as? PreparedReview.Deletion)?.review
                if (prepared == null || !describes(row, prepared)) {
                    mutate(row.localId, startedIn, setOf(DeletionPhase.PREPARING)) { it.copy(phase = DeletionPhase.REJECTED, errorCode = "review_mismatch", updatedAtMs = clock()) }
                        ?.let { PrepareOutcome.Mismatch(it) } ?: PrepareOutcome.Superseded
                } else {
                    val next = mutate(row.localId, startedIn, setOf(DeletionPhase.PREPARING)) {
                        it.copy(
                            phase = DeletionPhase.PREPARED,
                            errorCode = null,
                            reviewId = prepared.reviewId,
                            paramsDigest = prepared.paramsDigest,
                            expiresAtMs = prepared.expiresAtMs,
                            sourceRevisions = prepared.sourceRevisions,
                            concernRevisions = prepared.concernRevisions,
                            preview = StoredPreview(prepared.preview.attachments, prepared.preview.concernsErased, prepared.preview.concernsDetached, prepared.preview.derived, prepared.preview.expectedCount),
                            updatedAtMs = clock(),
                        )
                    }
                    if (next == null) {
                        PrepareOutcome.Superseded
                    } else {
                        rows.withLock { if (epoch == startedIn) subjects[row.localId] = prepared.preview.sources }
                        PrepareOutcome.Ready(next, prepared.preview.sources)
                    }
                }
            }
        }
    }

    /** The Worker's review must be for exactly the selection the owner made, with the preview and revisions of each email. */
    private fun describes(row: DeletionReview, prepared: PreparedDeletion): Boolean =
        prepared.sourceIds.sorted() == row.sourceIds &&
            prepared.preview.expectedCount == row.sourceIds.size &&
            prepared.preview.sources.map { it.id }.sorted() == row.sourceIds &&
            prepared.sourceRevisions.keys == row.sourceIds.toSet()

    private fun isExpired(review: DeletionReview, now: Long) = review.expiresAtMs?.let { now >= it } ?: false

    /** Whether the owner can still confirm [review] on the phone's clock. The Worker has the last word. */
    fun confirmable(review: DeletionReview, now: Long = clock()): Boolean =
        !isExpired(review, now) && (review.phase == DeletionPhase.PREPARED || review.phase == DeletionPhase.CONFIRMING ||
            (review.phase == DeletionPhase.SENDING && review.readback?.state == ReviewState.OPEN))

    private fun resendable(row: DeletionReview) = row.phase == DeletionPhase.SENDING && row.readback?.state == ReviewState.OPEN

    /**
     * Confirms with a fresh passkey assertion. The confirmation key is stored before the prompt. After the prompt the
     * row is read again: only if it still exists as the same review, unexpired, with unchanged emails, is it moved to
     * SENDING (the commit point) and sent; otherwise nothing is sent. A request that gets no answer is left unknown.
     */
    suspend fun confirm(localId: String, passkeys: PasskeyAuthenticator): ConfirmOutcome {
        val startedIn: Int
        val started = rows.withLock {
            startedIn = epoch
            if (localId in inFlight) return ConfirmOutcome.NotAllowed(store.get(localId)?.phase)
            val current = store.get(localId) ?: return ConfirmOutcome.NotAllowed(null)
            if ((current.phase != DeletionPhase.PREPARED && current.phase != DeletionPhase.CONFIRMING && !resendable(current)) || current.reviewId == null || current.paramsDigest == null) {
                return ConfirmOutcome.NotAllowed(current.phase)
            }
            if (isExpired(current, clock())) return ConfirmOutcome.Expired
            if (changedOnPhone(current)) {
                store.put(current.copy(phase = DeletionPhase.REJECTED, errorCode = "selection_changed", updatedAtMs = clock()))
                return ConfirmOutcome.Changed
            }
            inFlight += localId
            val key = current.confirmKey ?: newId()
            // Stored before the passkey prompt. A resend keeps its SENDING phase: it may already have been received.
            current.copy(phase = if (resendable(current)) DeletionPhase.SENDING else DeletionPhase.CONFIRMING, confirmKey = key, errorCode = null, updatedAtMs = clock()).also { store.put(it) }
        }
        try {
            when (val auth = recent.confirm(passkeys)) {
                is RecentAuthOutcome.Confirmed -> Unit
                RecentAuthOutcome.Cancelled -> return ConfirmOutcome.AuthCancelled
                RecentAuthOutcome.SignInRequired -> return ConfirmOutcome.SignIn
                is RecentAuthOutcome.Rejected -> return ConfirmOutcome.AuthFailed(auth.code)
                is RecentAuthOutcome.Unavailable -> return ConfirmOutcome.AuthFailed(auth.code)
            }
            // The commit point: everything that could have changed during the prompt is checked against the row as it is now.
            var refusal: ConfirmOutcome? = null
            val sending = mutate(localId, startedIn, setOf(DeletionPhase.CONFIRMING, DeletionPhase.SENDING)) { current ->
                when {
                    current.reviewId != started.reviewId || current.paramsDigest != started.paramsDigest || current.confirmKey != started.confirmKey || current.sourceIds != started.sourceIds -> null
                    isExpired(current, clock()) -> {
                        refusal = ConfirmOutcome.Expired
                        null
                    }
                    changedOnPhone(current) -> {
                        refusal = ConfirmOutcome.Changed
                        null
                    }
                    else -> current.copy(phase = DeletionPhase.SENDING, updatedAtMs = clock())
                }
            }
            if (sending == null) {
                if (refusal == ConfirmOutcome.Changed) mutate(localId, startedIn, setOf(DeletionPhase.CONFIRMING)) { it.copy(phase = DeletionPhase.REJECTED, errorCode = "selection_changed", updatedAtMs = clock()) }
                return refusal ?: ConfirmOutcome.Superseded
            }
            val result = api.confirm(sending.reviewId!!, sending.confirmKey!!, sending.paramsDigest!!)
            return settle(sending, startedIn, result)
        } finally {
            rows.withLock { inFlight -= localId }
        }
    }

    private suspend fun changedOnPhone(review: DeletionReview): Boolean {
        val held = archive.sources(review.sourceIds).first().associateBy { it.id }
        return review.sourceIds.any { id ->
            val source = held[id] ?: return@any true
            val reviewed = review.sourceRevisions[id] ?: return@any true
            source.revision > reviewed
        }
    }

    private suspend fun settle(sending: DeletionReview, startedIn: Int, result: ConfirmResult): ConfirmOutcome {
        val id = sending.localId
        return when (result) {
            is ConfirmResult.Confirmed -> {
                val receipt = result.confirmed.receipt as? OperationReceipt.Deletion
                if (receipt == null) unknown(sending, startedIn, "malformed_response") else complete(id, startedIn, result.confirmed.operationId, receipt, null)?.let { ConfirmOutcome.Deleted(it) } ?: current(id)
            }
            // The review was used, by this request or an earlier copy of it: the operation says whether the deletion happened.
            is ConfirmResult.AlreadyConsumed -> {
                mutate(id, startedIn, setOf(DeletionPhase.SENDING)) { it.copy(operationId = result.operationId ?: it.operationId, updatedAtMs = clock()) }
                when (val read = readbackIn(id, startedIn, own = true)) {
                    is ReadbackOutcome.Deleted -> ConfirmOutcome.Deleted(read.review)
                    else -> store.get(id)?.let { ConfirmOutcome.Unknown(it, "review_consumed") } ?: ConfirmOutcome.Superseded
                }
            }
            // Answered before anything was read or written: nothing was sent.
            ConfirmResult.RecentAuthRequired -> mutate(id, startedIn, setOf(DeletionPhase.SENDING)) { it.copy(phase = DeletionPhase.CONFIRMING, errorCode = "recent_auth_required", updatedAtMs = clock()) }
                ?.let { ConfirmOutcome.NotCompleted(it, "recent_auth_required") } ?: current(id)
            ConfirmResult.SessionExpired -> {
                mutate(id, startedIn, setOf(DeletionPhase.SENDING)) { it.copy(phase = DeletionPhase.CONFIRMING, updatedAtMs = clock()) }
                ConfirmOutcome.SignIn
            }
            is ConfirmResult.Rejected -> mutate(id, startedIn, setOf(DeletionPhase.SENDING)) { it.copy(phase = DeletionPhase.REJECTED, errorCode = result.code, updatedAtMs = clock()) }
                ?.let { ConfirmOutcome.Rejected(it) } ?: current(id)
            is ConfirmResult.NotCompleted -> mutate(id, startedIn, setOf(DeletionPhase.SENDING)) { it.copy(phase = DeletionPhase.CONFIRMING, errorCode = result.code, updatedAtMs = clock()) }
                ?.let { ConfirmOutcome.NotCompleted(it, result.code) } ?: current(id)
            is ConfirmResult.NoAnswer -> unknown(sending, startedIn, result.code)
        }
    }

    /** What the row says now, when a late answer found it already settled: the settled state wins and is reported as it is. */
    private suspend fun current(id: String): ConfirmOutcome {
        val row = store.get(id) ?: return ConfirmOutcome.Superseded
        return when (row.phase) {
            DeletionPhase.SUCCEEDED -> ConfirmOutcome.Deleted(row)
            DeletionPhase.REJECTED -> ConfirmOutcome.Rejected(row)
            DeletionPhase.SENDING -> ConfirmOutcome.Unknown(row, row.errorCode ?: "unknown")
            else -> ConfirmOutcome.NotAllowed(row.phase)
        }
    }

    private suspend fun unknown(sending: DeletionReview, startedIn: Int, code: String): ConfirmOutcome =
        mutate(sending.localId, startedIn, setOf(DeletionPhase.SENDING)) { it.copy(errorCode = code, updatedAtMs = clock()) }
            ?.let { ConfirmOutcome.Unknown(it, code) } ?: current(sending.localId)

    /**
     * Persists the receipt first, then removes the phone's private copies, so a crash between the two is repaired by
     * [recover]. Returns null when the row was settled or dropped by someone else in the meantime (then nothing is purged here).
     */
    private suspend fun complete(localId: String, startedIn: Int, operationId: String, receipt: OperationReceipt.Deletion, cleanup: Cleanup?, readback: StoredReadback? = null): DeletionReview? {
        val done = mutate(localId, startedIn, UNSETTLED_PHASES) {
            it.copy(
                phase = DeletionPhase.SUCCEEDED,
                errorCode = null,
                operationId = operationId,
                receipt = receipt,
                cleanup = cleanup,
                cleanupCheckedAtMs = cleanup?.let { clock() },
                readback = readback ?: it.readback,
                updatedAtMs = clock(),
            )
        } ?: return null
        purge.purge(done.sourceIds, clock())
        return done
    }

    /**
     * Read-only recovery after a confirmation with no recorded answer: `GET /reviews/{id}`, then `GET /operations/{id}`
     * when it was consumed. It changes nothing on the Worker and sends no confirmation.
     */
    suspend fun readback(localId: String): ReadbackOutcome = readbackIn(localId, epochNow(), own = false)

    private suspend fun readbackIn(localId: String, startedIn: Int, own: Boolean): ReadbackOutcome {
        val row = store.get(localId) ?: return ReadbackOutcome.NothingToRead
        val reviewId = row.reviewId ?: return ReadbackOutcome.NothingToRead
        if (row.phase !in UNSETTLED_PHASES) return ReadbackOutcome.NothingToRead
        // A confirmation this process is sending is settled by its own answer, not by a read racing it (the confirmation itself may read back).
        if (!own && rows.withLock { localId in inFlight }) return ReadbackOutcome.NothingToRead
        val read = when (val r = api.readReview(reviewId)) {
            is ReviewRead.Ok -> r.value
            ReviewRead.SessionExpired -> return ReadbackOutcome.SignIn
            ReviewRead.NotKnown -> return unsettled(row, startedIn, UnsettledReason.NOT_KNOWN, "not_known", null)
            is ReviewRead.Unavailable -> return unsettled(row, startedIn, UnsettledReason.UNAVAILABLE, r.code, null)
        }
        if (!describes(row, read)) return unsettled(row, startedIn, UnsettledReason.MISMATCH, "readback_mismatch", null)
        val stored = StoredReadback(read.state, read.operationState, clock())
        return when (read.state) {
            ReviewState.OPEN -> unsettled(row, startedIn, UnsettledReason.OPEN, null, stored)
            ReviewState.EXPIRED ->
                if (clock() >= read.expiresAtMs + EXPIRY_GRACE_MS && localId !in rows.withLock { inFlight.toSet() }) {
                    mutate(localId, startedIn, setOf(DeletionPhase.SENDING, DeletionPhase.CONFIRMING)) { it.copy(phase = DeletionPhase.REJECTED, errorCode = "review_expired_unused", readback = stored, updatedAtMs = clock()) }
                        ?.let { ReadbackOutcome.Unused(it) } ?: settledAlready(localId)
                } else {
                    unsettled(row, startedIn, UnsettledReason.EXPIRED_RECENTLY, null, stored)
                }
            ReviewState.CONSUMED -> operation(row, startedIn, read, stored)
        }
    }

    /** A read that lost the race to another settlement reports that settlement instead of writing its own. */
    private suspend fun settledAlready(localId: String): ReadbackOutcome {
        val row = store.get(localId) ?: return ReadbackOutcome.NothingToRead
        return if (row.phase == DeletionPhase.SUCCEEDED) ReadbackOutcome.Deleted(row) else ReadbackOutcome.NothingToRead
    }

    private fun describes(row: DeletionReview, read: ReviewReadback) =
        read.reviewId == row.reviewId && read.paramsDigest == row.paramsDigest && read.effect == ReviewEffect.ARCHIVE_DELETE

    private suspend fun operation(row: DeletionReview, startedIn: Int, read: ReviewReadback, stored: StoredReadback): ReadbackOutcome {
        val operationId = read.consumedOperationId ?: return unsettled(row, startedIn, UnsettledReason.OPERATION_UNSETTLED, "operation_missing", stored)
        return when (val op = api.readOperation(operationId)) {
            ReviewRead.SessionExpired -> ReadbackOutcome.SignIn
            ReviewRead.NotKnown -> unsettled(row, startedIn, UnsettledReason.NOT_KNOWN, "operation_not_known", stored, operationId)
            is ReviewRead.Unavailable -> unsettled(row, startedIn, UnsettledReason.UNAVAILABLE, op.code, stored, operationId)
            is ReviewRead.Ok -> {
                val operation = op.value
                val receipt = operation.receipt as? OperationReceipt.Deletion
                when {
                    operation.id != operationId || operation.reviewId != row.reviewId || operation.effect != ReviewEffect.ARCHIVE_DELETE || operation.sourceIds.sorted() != row.sourceIds ->
                        unsettled(row, startedIn, UnsettledReason.MISMATCH, "operation_mismatch", stored, operationId)
                    operation.state == OperationState.SUCCEEDED && receipt != null ->
                        complete(row.localId, startedIn, operationId, receipt, operation.cleanup, stored)?.let { ReadbackOutcome.Deleted(it) } ?: settledAlready(row.localId)
                    operation.state == OperationState.FAILED || operation.state == OperationState.CANCELLED ->
                        mutate(row.localId, startedIn, UNSETTLED_PHASES) { it.copy(phase = DeletionPhase.REJECTED, errorCode = operation.errorCode ?: "operation_${operation.state.wire}", readback = stored, operationId = operationId, updatedAtMs = clock()) }
                            ?.let { ReadbackOutcome.Unsettled(it, UnsettledReason.OPERATION_UNSETTLED) } ?: settledAlready(row.localId)
                    else -> unsettled(row, startedIn, UnsettledReason.OPERATION_UNSETTLED, "operation_${operation.state.wire}", stored, operationId)
                }
            }
        }
    }

    /** Records what a read-back found without settling anything. Written against the row as it is now: a settled row is left as it is. */
    private suspend fun unsettled(row: DeletionReview, startedIn: Int, reason: UnsettledReason, code: String?, readback: StoredReadback?, operationId: String? = null): ReadbackOutcome =
        mutate(row.localId, startedIn, UNSETTLED_PHASES) {
            it.copy(errorCode = code ?: it.errorCode, readback = readback ?: it.readback, operationId = operationId ?: it.operationId, updatedAtMs = clock())
        }?.let { ReadbackOutcome.Unsettled(it, reason) } ?: settledAlready(row.localId)

    /** Reads the operation again to learn how far cleanup of the live archive has come. Cleanup is asynchronous and says nothing about backups or export files. */
    suspend fun refreshCleanup(localId: String): Boolean {
        val startedIn = epochNow()
        val row = store.get(localId)?.takeIf { it.phase == DeletionPhase.SUCCEEDED } ?: return false
        val operationId = row.operationId ?: return false
        val operation = (api.readOperation(operationId) as? ReviewRead.Ok)?.value ?: return false
        if (operation.reviewId != row.reviewId || operation.cleanup == null) return false
        return mutate(localId, startedIn, setOf(DeletionPhase.SUCCEEDED)) { it.copy(cleanup = operation.cleanup, cleanupCheckedAtMs = clock(), updatedAtMs = clock()) } != null
    }

    /** The owner gives up tracking an outcome that stayed unknown. The deletion may still have happened; sync will show it. */
    suspend fun stopTracking(localId: String) {
        mutate(localId, epochNow(), setOf(DeletionPhase.SENDING)) { it.copy(phase = DeletionPhase.STOPPED, updatedAtMs = clock()) }
    }

    /** Removes a row that no longer needs the owner: anything but a confirmation with an unknown outcome. */
    suspend fun dismiss(localId: String): Boolean = rows.withLock {
        val row = store.get(localId) ?: return@withLock false
        if (row.phase == DeletionPhase.SENDING || localId in inFlight) return@withLock false
        store.delete(localId)
        subjects.remove(localId)
        true
    }

    /** Start of the app. Repairs the purge of a deletion whose receipt was stored but whose cleanup of the phone was cut short, and drops old finished rows. No request is made. */
    suspend fun recover() {
        val now = clock()
        for (row in store.all()) {
            when {
                row.phase == DeletionPhase.SUCCEEDED && now - row.updatedAtMs < TERMINAL_KEEP_MS -> purge.purge(row.sourceIds, now)
                row.phase in TERMINAL_PHASES && now - row.updatedAtMs >= TERMINAL_KEEP_MS -> store.delete(row.localId)
            }
        }
    }

    companion object {
        fun validLocalId(id: String) = isUuid(id)
    }
}
