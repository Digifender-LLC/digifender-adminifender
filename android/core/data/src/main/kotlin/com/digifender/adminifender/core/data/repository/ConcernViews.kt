package com.digifender.adminifender.core.data.repository

import com.digifender.adminifender.core.data.sync.MutationState
import com.digifender.adminifender.core.data.sync.QueuedMutation
import com.digifender.adminifender.core.model.Concern
import com.digifender.adminifender.core.model.Importance
import com.digifender.adminifender.core.model.Status
import com.digifender.adminifender.core.model.Transition
import com.digifender.adminifender.core.model.applyOptimistic

/**
 * A concern as the owner sees it: the server copy with queued edits applied on top. [pending] means at least one
 * edit has not been confirmed; [conflict] and [failed] are edits that need the owner and are not applied.
 */
data class ConcernView(
    val server: Concern,
    val shown: Concern,
    val pending: Int,
    val conflicts: List<QueuedMutation>,
    val failed: List<QueuedMutation>,
    val held: Boolean,
)

/** Inbox filters. Status stays independent of read, acknowledged and importance. */
enum class InboxFilter { ACTIVE, WAITING, SNOOZED, RESOLVED }

/**
 * Applies queued and held edits in creation order. An edit that no longer applies (for example the server already
 * resolved the concern) is left to the outbox to settle rather than shown as done.
 */
fun overlay(concerns: List<Concern>, mutations: List<QueuedMutation>, nowMs: Long): List<ConcernView> {
    val byConcern = mutations.groupBy { it.mutation.concernId }
    return concerns.map { server ->
        val own = byConcern[server.id].orEmpty()
        var shown = server
        var pending = 0
        for (q in own) {
            if (q.state != MutationState.QUEUED && q.state != MutationState.HELD) continue
            pending++
            val t = applyOptimistic(shown, q.mutation.action, q.mutation.createdAtMs)
            if (t is Transition.Applied) shown = t.concern
        }
        ConcernView(
            server = server,
            shown = shown,
            pending = pending,
            conflicts = own.filter { it.state == MutationState.CONFLICT },
            failed = own.filter { it.state == MutationState.FAILED },
            held = own.any { it.state == MutationState.HELD },
        ).let { if (shouldWake(it.shown, nowMs)) it.copy(shown = it.shown.copy(status = Status.OPEN)) else it }
    }
}

/** A snooze that has expired shows as open even before the server's next sweep reaches the phone. */
private fun shouldWake(c: Concern, nowMs: Long) = c.status == Status.SNOOZED && (c.snoozeUntilMs ?: Long.MAX_VALUE) <= nowMs

private fun Importance.rank() = when (this) {
    Importance.CRITICAL -> 0
    Importance.URGENT -> 1
    Importance.IMPORTANT -> 2
    Importance.ROUTINE -> 3
}

fun matches(filter: InboxFilter, c: Concern): Boolean = when (filter) {
    InboxFilter.ACTIVE -> c.status == Status.OPEN
    InboxFilter.WAITING -> c.status == Status.WAITING
    InboxFilter.SNOOZED -> c.status == Status.SNOOZED
    InboxFilter.RESOLVED -> c.status == Status.RESOLVED
}

/**
 * Items needing the owner first: anything with an unresolved conflict or failure, then by importance, then by the
 * nearest due date, then most recently updated. Resolved items are listed most recently resolved first.
 */
fun inbox(views: List<ConcernView>, filter: InboxFilter): List<ConcernView> {
    val selected = views.filter { matches(filter, it.shown) }
    if (filter == InboxFilter.RESOLVED) return selected.sortedWith(compareByDescending<ConcernView> { it.shown.resolvedAtMs ?: it.shown.updatedAtMs }.thenBy { it.shown.id })
    return selected.sortedWith(
        compareBy<ConcernView> { if (it.conflicts.isEmpty() && it.failed.isEmpty()) 1 else 0 }
            .thenBy { it.shown.importance.rank() }
            .thenBy { it.shown.due?.atMs ?: Long.MAX_VALUE }
            .thenByDescending { it.shown.updatedAtMs }
            .thenBy { it.shown.id },
    )
}

/** Freshness the UI must show with cached data (FR-002): never claims a sync that did not happen. */
data class Freshness(val lastSyncedAtMs: Long?, val needsFullDownload: Boolean, val queued: Int, val needsOwner: Int, val held: Boolean)

fun freshness(lastSyncedAtMs: Long?, needsBootstrap: Boolean, mutations: List<QueuedMutation>) = Freshness(
    lastSyncedAtMs = lastSyncedAtMs,
    needsFullDownload = needsBootstrap,
    queued = mutations.count { it.state == MutationState.QUEUED || it.state == MutationState.HELD },
    needsOwner = mutations.count { it.state == MutationState.CONFLICT || it.state == MutationState.FAILED },
    held = mutations.any { it.state == MutationState.HELD },
)

/** Section size the Worker uses for briefing cards; the phone's own computation keeps the same bound. */
const val BRIEFING_SECTION_LIMIT = 100

/**
 * The two briefing sections the phone can compute from its own cache: unresolved items past a confirmed due date, and
 * open or waiting items nobody acknowledged. It cannot know what is new or changed since the last review, and it uses
 * the phone's clock rather than the server's, so the screen labels it as a partial, cached view.
 */
data class LocalBriefing(val overdue: List<ConcernView>, val unacknowledged: List<ConcernView>)

fun localBriefing(views: List<ConcernView>, nowMs: Long): LocalBriefing {
    val overdue = views.filter { v -> v.shown.status != Status.RESOLVED && (v.shown.due?.atMs ?: Long.MAX_VALUE) < nowMs }
        .sortedWith(compareBy<ConcernView> { it.shown.due?.atMs }.thenBy { it.shown.id })
    val unacknowledged = views.filter { v -> (v.shown.status == Status.OPEN || v.shown.status == Status.WAITING) && v.shown.acknowledgedAtMs == null }
        .sortedWith(compareByDescending<ConcernView> { it.shown.updatedAtMs }.thenByDescending { it.shown.id })
    return LocalBriefing(overdue.take(BRIEFING_SECTION_LIMIT), unacknowledged.take(BRIEFING_SECTION_LIMIT))
}
