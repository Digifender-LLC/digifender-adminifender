package com.digifender.adminifender.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.digifender.adminifender.core.data.network.SourceSummary
import com.digifender.adminifender.core.data.repository.BriefingItem
import com.digifender.adminifender.core.data.repository.BriefingRecord
import com.digifender.adminifender.core.data.repository.BriefingRepository
import com.digifender.adminifender.core.data.repository.CachedReason
import com.digifender.adminifender.core.data.repository.ConcernRepository
import com.digifender.adminifender.core.data.repository.ConcernView
import com.digifender.adminifender.core.data.repository.RefreshOutcome
import com.digifender.adminifender.core.data.repository.ReviewOutcome
import com.digifender.adminifender.core.data.repository.ReviewPhase
import com.digifender.adminifender.core.data.repository.StoredReview
import com.digifender.adminifender.core.data.repository.localBriefing
import com.digifender.adminifender.core.model.Concern
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val STOP_TIMEOUT_MS = 5_000L

/**
 * A briefing line as the owner sees it: the synced concern with queued edits applied. [behind] means the snapshot
 * showed a newer revision than the phone has synced; [pending] and [needsOwner] mirror the inbox markers.
 */
data class BriefingCard(val concern: Concern, val behind: Boolean, val pending: Boolean, val needsOwner: Boolean)

data class BriefingSections(
    val newItems: List<BriefingCard>,
    val changed: List<BriefingCard>,
    val overdue: List<BriefingCard>,
    val unacknowledged: List<BriefingCard>,
    /** Distinct concerns and emails the snapshot listed that cannot be shown from the synced cache (not synced yet, or deleted). */
    val notOnPhone: Int,
)

/**
 * Why a new "mark reviewed" is not offered. Reviewing advances the server's cursor through the whole snapshot, so it is
 * offered only when everything the snapshot lists can be shown from this phone's synced cache.
 */
enum class ReviewBlock {
    /** A listed concern or email is not in the cache (not synced yet, deleted or no longer eligible to show). */
    NOT_ON_PHONE,

    /** A listed concern is cached at an older revision than the snapshot covers. */
    BEHIND,
}

sealed interface BriefingContent {
    /**
     * The server's snapshot, read from the cache it was stored in. Reviewing it covers exactly this. [newSources] are
     * only cached emails that are ready and normal; a deleted or quarantined one is never listed, and counts in
     * [BriefingSections.notOnPhone] instead.
     */
    data class FromServer(
        val id: String,
        val throughTimeMs: Long,
        val lastReviewedAtMs: Long?,
        val fetchedAtMs: Long,
        val sections: BriefingSections,
        val newSources: List<SourceSummary>,
        val review: StoredReview,
        val reviewBlock: ReviewBlock?,
        /** The server held back further new or changed work; marking this reviewed leaves it for the next briefing. */
        val hasMore: Boolean = false,
    ) : BriefingContent {
        /**
         * A first review needs [reviewBlock] to be null. An attempt that was sent and never answered may be repeated
         * even if the cache has changed since: it names the snapshot the owner was looking at when they asked, and the
         * repeat only learns what the server did with that same request.
         */
        val canReview: Boolean get() = review.phase == ReviewPhase.UNCONFIRMED || (review.phase == ReviewPhase.NONE && reviewBlock == null)
    }

    /** No snapshot has been read: only what this phone can work out itself, labelled as such and not reviewable. */
    data class FromPhone(val overdue: List<BriefingCard>, val unacknowledged: List<BriefingCard>) : BriefingContent
}

enum class ReviewMessage { REVIEWED, ALREADY, NOT_CONFIRMED, SIGN_IN, GONE, REFUSED, CHANGED }

/** [content] is null only until the cache has been read the first time. [problem] is why the last refresh did not succeed. */
data class BriefingUiState(val content: BriefingContent?, val loading: Boolean, val problem: CachedReason?, val sending: Boolean, val message: ReviewMessage?)

private fun card(view: ConcernView, snapshotRevision: Int? = null) = BriefingCard(
    concern = view.shown,
    behind = snapshotRevision != null && view.server.revision < snapshotRevision,
    pending = view.pending > 0,
    needsOwner = view.conflicts.isNotEmpty() || view.failed.isNotEmpty(),
)

/** Joins a stored snapshot with the synced cache. Pure: the screen state is a function of these inputs. */
fun buildBriefingContent(record: BriefingRecord?, views: List<ConcernView>, cachedSources: List<SourceSummary>, nowMs: Long): BriefingContent {
    if (record == null) {
        val local = localBriefing(views, nowMs)
        return BriefingContent.FromPhone(local.overdue.map { card(it) }, local.unacknowledged.map { card(it) })
    }
    val byId = views.associateBy { it.server.id }
    val b = record.briefing
    fun section(items: List<BriefingItem>) = items.mapNotNull { item -> byId[item.concernId]?.let { card(it, item.revision) } }
    val sections = listOf(section(b.newItems), section(b.changedItems), section(b.overdueItems), section(b.unacknowledgedItems))
    val missingConcerns = (b.newItems + b.changedItems + b.overdueItems + b.unacknowledgedItems).map { it.concernId }.distinct().count { it !in byId }
    val showable = cachedSources.filter { it.state == "ready" && !it.quarantined }.associateBy { it.id }
    val newSources = b.newSourceIds.mapNotNull { showable[it] }
    val missing = missingConcerns + (b.newSourceIds.distinct().size - newSources.map { it.id }.distinct().size)
    val block = when {
        missing > 0 -> ReviewBlock.NOT_ON_PHONE
        sections.any { cards -> cards.any { it.behind } } -> ReviewBlock.BEHIND
        else -> null
    }
    return BriefingContent.FromServer(
        id = b.id,
        throughTimeMs = b.throughTimeMs,
        lastReviewedAtMs = b.lastReviewedAtMs,
        fetchedAtMs = b.fetchedAtMs,
        sections = BriefingSections(sections[0], sections[1], sections[2], sections[3], missing),
        newSources = newSources,
        review = record.review,
        reviewBlock = block,
        hasMore = b.hasMore,
    )
}

private data class Transient(val loading: Boolean = false, val problem: CachedReason? = null, val sending: Boolean = false, val message: ReviewMessage? = null)

/**
 * Briefing screen state. Opening the screen reads the briefing but never reviews it: only [markReviewed] does, only for
 * the snapshot on screen, and only when everything it lists can be shown from the synced cache (FR-020). The screen
 * shows what the cache holds; a refresh writes to the cache first.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BriefingViewModel(
    private val briefings: BriefingRepository,
    private val concerns: ConcernRepository,
    private val cachedSources: (List<String>) -> Flow<List<SourceSummary>>,
    private val clock: () -> Long,
) : ViewModel() {
    private val transient = MutableStateFlow(Transient(loading = true))

    /** The stored snapshot with the cached summaries of the emails it lists; both come from Room. */
    private val snapshot = briefings.current().flatMapLatest { record ->
        val ids = record?.briefing?.newSourceIds.orEmpty()
        (if (ids.isEmpty()) flowOf(emptyList()) else cachedSources(ids)).map { record to it }
    }

    val state: StateFlow<BriefingUiState> = combine(snapshot, concerns.views(), transient) { (record, sources), views, t ->
        BriefingUiState(buildBriefingContent(record, views, sources, clock()), t.loading, t.problem, t.sending, t.message)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), BriefingUiState(null, true, null, false, null))

    init {
        load()
    }

    /**
     * The briefing screen was entered again: from another tab, back from a concern, or by a notification while this
     * model was already alive. An old result message is dismissed and the briefing is read again, since new mail or a
     * review elsewhere may have moved it on. The stored snapshot, and any unconfirmed review intent in it, stay on
     * screen until the read replaces them; reading never reviews. Ignored work follows [refresh].
     */
    fun onOpened() {
        if (transient.value.sending) return
        transient.update { it.copy(message = null) }
        refresh()
    }

    /** Reads the current briefing from the server into the cache. Ignored while a read or a review is already running. */
    fun refresh() {
        if (transient.value.loading || transient.value.sending) return
        transient.update { it.copy(loading = true, message = null) }
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val outcome = briefings.refresh()
            transient.update { it.copy(loading = false, problem = (outcome as? RefreshOutcome.Failed)?.reason) }
        }
    }

    /** Marks the snapshot on screen reviewed. Repeats of an unconfirmed attempt send the same request. */
    fun markReviewed() {
        val t = transient.value
        val content = state.value.content as? BriefingContent.FromServer ?: return
        if (t.loading || t.sending || !content.canReview) return
        transient.update { it.copy(sending = true, message = null) }
        viewModelScope.launch {
            val message = when (briefings.markReviewed(content.id)) {
                is ReviewOutcome.Reviewed -> ReviewMessage.REVIEWED
                ReviewOutcome.AlreadyReviewed -> ReviewMessage.ALREADY
                ReviewOutcome.NotConfirmed -> ReviewMessage.NOT_CONFIRMED
                ReviewOutcome.SignIn -> ReviewMessage.SIGN_IN
                ReviewOutcome.Gone -> ReviewMessage.GONE
                ReviewOutcome.Stale -> ReviewMessage.CHANGED
                is ReviewOutcome.Refused -> ReviewMessage.REFUSED
            }
            transient.update { it.copy(sending = false, message = message) }
        }
    }

    fun messageShown() {
        transient.update { it.copy(message = null) }
    }
}
