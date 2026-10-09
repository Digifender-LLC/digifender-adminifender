package com.digifender.adminifender.core.data.repository

import com.digifender.adminifender.core.data.network.BriefingApi
import com.digifender.adminifender.core.data.network.BriefingDto
import com.digifender.adminifender.core.data.network.ConcernDto
import com.digifender.adminifender.core.data.network.ReadResult
import com.digifender.adminifender.core.data.network.ReviewResult
import com.digifender.adminifender.core.data.network.isBriefingId
import com.digifender.adminifender.core.data.network.isRecordId
import com.digifender.adminifender.core.data.network.toDomain
import com.digifender.adminifender.core.data.network.wireJson
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/** A concern the snapshot listed, by ID and the revision the server showed. Titles come from the synced cache. */
data class BriefingItem(val concernId: String, val revision: Int)

/**
 * One server snapshot (lld.md#briefings), kept by reference. The sections are exactly what the Worker listed at
 * [snapshotSeq]; marking the briefing reviewed covers that snapshot and nothing later.
 */
data class Briefing(
    val id: String,
    val snapshotSeq: Long,
    val throughTimeMs: Long,
    val lastReviewedAtMs: Long?,
    val fetchedAtMs: Long,
    val newItems: List<BriefingItem>,
    val changedItems: List<BriefingItem>,
    val overdueItems: List<BriefingItem>,
    val unacknowledgedItems: List<BriefingItem>,
    val newSourceIds: List<String>,
    /** The Worker left work out of a capped section, so a later briefing has more; this one covers only what it lists. */
    val hasMore: Boolean = false,
)

/** [UNCONFIRMED] means a review was sent and no answer came back: the owner must try again, with the same key. */
enum class ReviewPhase { NONE, UNCONFIRMED, DONE }

/** The owner's mark-reviewed intent for one briefing. [reviewedAtMs] is null when only "already reviewed" is known. */
data class StoredReview(val phase: ReviewPhase, val key: String? = null, val reviewedAtMs: Long? = null) {
    companion object {
        val NONE = StoredReview(ReviewPhase.NONE)
    }
}

data class BriefingRecord(val briefing: Briefing, val review: StoredReview)

/** Room in the app, in memory in tests. Holds at most one briefing; the UI reads only from [current]. */
interface BriefingCache {
    fun current(): Flow<BriefingRecord?>

    /** Stores [briefing] in one transaction. The review state is kept only when it is the same briefing as the stored one. */
    suspend fun replace(briefing: Briefing)

    /** Updates the review state if [briefingId] is still the stored briefing; returns whether it was. */
    suspend fun setReview(briefingId: String, review: StoredReview): Boolean
}

sealed interface RefreshOutcome {
    /** The briefing is in the cache and published from there. */
    data object Stored : RefreshOutcome

    /** Nothing was stored. [CachedReason.UNAVAILABLE] also covers an answer the phone could not parse. */
    data class Failed(val reason: CachedReason) : RefreshOutcome
}

sealed interface ReviewOutcome {
    data class Reviewed(val reviewedAtMs: Long) : ReviewOutcome

    /** Reviewed earlier, for example by a send whose answer was lost. Nothing further was changed. */
    data object AlreadyReviewed : ReviewOutcome

    /** Nothing is confirmed. The next attempt for the same briefing reuses the same key, even after the app restarts. */
    data object NotConfirmed : ReviewOutcome

    data object SignIn : ReviewOutcome

    /** The server has no such briefing any more. */
    data object Gone : ReviewOutcome

    /** The briefing on screen is not the one stored; nothing was sent. */
    data object Stale : ReviewOutcome

    data class Refused(val code: String) : ReviewOutcome
}

private fun ConcernDto.toItem(): BriefingItem {
    // The whole projection must parse, as for sync: an unknown value is an error, not something to skip.
    requireNotNull(toDomain()) { "concern_unknown_value" }
    require(isRecordId(id)) { "concern_id" }
    return BriefingItem(id, revision)
}

private fun BriefingDto.toBriefing(fetchedAtMs: Long): Briefing {
    require(isBriefingId(id)) { "briefing_id" }
    require(newSourceIds.all(::isRecordId)) { "source_id" }
    return Briefing(
        id = id,
        snapshotSeq = snapshotSeq,
        throughTimeMs = Instant.parse(throughTime).toEpochMilli(),
        lastReviewedAtMs = lastReviewedAt?.let { Instant.parse(it).toEpochMilli() },
        fetchedAtMs = fetchedAtMs,
        newItems = newConcerns.map { it.toItem() },
        changedItems = changedConcerns.map { it.toItem() },
        overdueItems = overdue.map { it.toItem() },
        unacknowledgedItems = unacknowledged.map { it.toItem() },
        newSourceIds = newSourceIds,
        hasMore = hasMore,
    )
}

/**
 * The briefing as a server read that enters Room before the screen sees it (lld.md#sync). Fetching or showing it
 * never marks anything reviewed; only [markReviewed] does, for the exact briefing on screen. The review intent and its
 * idempotency key are written to the cache, and that write is confirmed, before the request is sent, so a send with no
 * answer is repeated as the same request after a restart. Reviews are not dispatched automatically on reconnect: the
 * owner decides when to try again.
 *
 * Sign-out calls [invalidate] before it clears the cache: a read still in flight is then discarded instead of
 * repopulating the cleared cache, and a read that finishes after a newer one never replaces it.
 */
class BriefingRepository(
    private val api: BriefingApi,
    private val cache: BriefingCache,
    private val clock: () -> Long,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
) {
    /** Short lock around the check-and-write of a refresh and around [invalidate]; never held across a request. */
    private val writeLock = Mutex()

    /** One mark-reviewed attempt at a time, so two callers for one briefing cannot create two keys. */
    private val reviewLock = Mutex()
    private var generation = 0
    private var nextTicket = 0L
    private var appliedTicket = 0L

    fun current(): Flow<BriefingRecord?> = cache.current()

    suspend fun refresh(): RefreshOutcome {
        val (startedIn, ticket) = writeLock.withLock { generation to ++nextTicket }
        val fetchedAt = clock()
        val briefing = when (val result = api.currentBriefing()) {
            is ReadResult.Ok -> try {
                result.value.toBriefing(fetchedAt)
            } catch (_: IllegalArgumentException) {
                return RefreshOutcome.Failed(CachedReason.UNAVAILABLE)
            } catch (_: DateTimeParseException) {
                return RefreshOutcome.Failed(CachedReason.UNAVAILABLE)
            }
            ReadResult.SessionExpired -> return RefreshOutcome.Failed(CachedReason.SIGN_IN)
            is ReadResult.Unavailable -> return RefreshOutcome.Failed(if (result.code == "network") CachedReason.OFFLINE else CachedReason.UNAVAILABLE)
            ReadResult.CursorExpired -> return RefreshOutcome.Failed(CachedReason.UNAVAILABLE)
        }
        return writeLock.withLock {
            when {
                // Signed out while the request was in flight: nothing of this response may reach the cache.
                generation != startedIn -> RefreshOutcome.Failed(CachedReason.SIGN_IN)
                // A newer read already landed; the cache holds something at least as new.
                ticket < appliedTicket -> RefreshOutcome.Stored
                (cache.current().first()?.briefing?.snapshotSeq ?: Long.MIN_VALUE) > briefing.snapshotSeq -> RefreshOutcome.Stored
                else -> {
                    cache.replace(briefing)
                    appliedTicket = ticket
                    RefreshOutcome.Stored
                }
            }
        }
    }

    /** Ends the current session's use of the cache: reads started before this call can no longer write. Call before clearing it. */
    suspend fun invalidate() {
        writeLock.withLock { generation++ }
    }

    /** Marks [displayedId] reviewed through its own snapshot. Sends nothing unless it is still the stored briefing. */
    suspend fun markReviewed(displayedId: String): ReviewOutcome = reviewLock.withLock { review(displayedId) }

    private suspend fun review(displayedId: String): ReviewOutcome {
        val record = cache.current().first()?.takeIf { it.briefing.id == displayedId } ?: return ReviewOutcome.Stale
        if (record.review.phase == ReviewPhase.DONE) return ReviewOutcome.AlreadyReviewed
        val key = record.review.key ?: newKey()
        // Persist the intent first. If the row was replaced or cleared meanwhile, nothing is sent for it.
        if (!cache.setReview(displayedId, StoredReview(ReviewPhase.UNCONFIRMED, key))) return ReviewOutcome.Stale
        return when (val result = api.reviewBriefing(displayedId, key)) {
            is ReviewResult.Reviewed -> {
                if (result.id != displayedId || result.throughSeq != record.briefing.snapshotSeq) {
                    // An answer about another snapshot confirms nothing about this one.
                    settle(displayedId, ReviewOutcome.Refused("mismatched_response"))
                } else {
                    cache.setReview(displayedId, StoredReview(ReviewPhase.DONE, null, result.reviewedAtMs))
                    ReviewOutcome.Reviewed(result.reviewedAtMs)
                }
            }
            ReviewResult.AlreadyReviewed -> {
                cache.setReview(displayedId, StoredReview(ReviewPhase.DONE))
                ReviewOutcome.AlreadyReviewed
            }
            ReviewResult.NoAnswer -> ReviewOutcome.NotConfirmed
            ReviewResult.NotFound -> settle(displayedId, ReviewOutcome.Gone)
            is ReviewResult.Rejected -> settle(displayedId, ReviewOutcome.Refused(result.code))
            // The server refused the token, so the review had no effect and no intent is left over.
            ReviewResult.SessionExpired -> settle(displayedId, ReviewOutcome.SignIn)
        }
    }

    private suspend fun settle(id: String, outcome: ReviewOutcome): ReviewOutcome {
        cache.setReview(id, StoredReview.NONE)
        return outcome
    }
}

/** Stores a [Briefing]'s sections as JSON. Pure, so the Room cache and the tests share it. */
internal object BriefingItemsCodec {
    @Serializable
    private data class Item(val id: String, val revision: Int)

    @Serializable
    private data class Stored(val new: List<Item>, val changed: List<Item>, val overdue: List<Item>, val unacknowledged: List<Item>, val sources: List<String>, val more: Boolean = false)

    private val json = wireJson

    fun encode(b: Briefing): String = json.encodeToString(
        Stored.serializer(),
        Stored(b.newItems.map(::item), b.changedItems.map(::item), b.overdueItems.map(::item), b.unacknowledgedItems.map(::item), b.newSourceIds, b.hasMore),
    )

    /** Fills the sections of [base] from [text]; throws IllegalArgumentException on a damaged row. */
    fun decode(text: String, base: Briefing): Briefing {
        val s = json.decodeFromString(Stored.serializer(), text)
        return base.copy(
            newItems = s.new.map(::back),
            changedItems = s.changed.map(::back),
            overdueItems = s.overdue.map(::back),
            unacknowledgedItems = s.unacknowledged.map(::back),
            newSourceIds = s.sources,
            hasMore = s.more,
        )
    }

    private fun item(i: BriefingItem) = Item(i.concernId, i.revision)

    private fun back(i: Item) = BriefingItem(i.id, i.revision)
}
