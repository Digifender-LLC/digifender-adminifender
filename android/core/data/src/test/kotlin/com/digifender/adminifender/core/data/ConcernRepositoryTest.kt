package com.digifender.adminifender.core.data

import com.digifender.adminifender.core.data.repository.ActResult
import com.digifender.adminifender.core.data.repository.CacheSource
import com.digifender.adminifender.core.data.repository.ConcernRepository
import com.digifender.adminifender.core.data.repository.InboxFilter
import com.digifender.adminifender.core.data.repository.SyncScheduler
import com.digifender.adminifender.core.data.repository.freshness
import com.digifender.adminifender.core.data.repository.inbox
import com.digifender.adminifender.core.data.repository.overlay
import com.digifender.adminifender.core.data.sync.MutationState
import com.digifender.adminifender.core.data.sync.QueuedMutation
import com.digifender.adminifender.core.model.Concern
import com.digifender.adminifender.core.model.ConcernAction
import com.digifender.adminifender.core.model.Due
import com.digifender.adminifender.core.model.Importance
import com.digifender.adminifender.core.model.OutboxDecision
import com.digifender.adminifender.core.model.PendingMutation
import com.digifender.adminifender.core.model.Status
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val NOW = 1_800_000_000_000L

private fun concern(id: String, importance: Importance = Importance.ROUTINE, status: Status = Status.OPEN, updated: Long = NOW - 1000, dueAt: Long? = null, revision: Int = 1) =
    Concern(id, revision, "T $id", status, importance, null, null, null, null, null, null, dueAt?.let { Due(it, "UTC", null, 1) }, emptyList(), updated)

private fun queued(key: String, id: String, action: ConcernAction, state: MutationState = MutationState.QUEUED, at: Long = NOW - 500) =
    QueuedMutation(PendingMutation(key, id, 1, action, at), state, at, null, null)

class ConcernViewsTest {
    @Test
    fun queuedEditsShowOnTopOfTheServerCopyAndConflictsDoNot() {
        val views = overlay(
            listOf(concern("a"), concern("b")),
            listOf(
                queued("k1", "a", ConcernAction.Resolve),
                queued("k2", "b", ConcernAction.Resolve, MutationState.CONFLICT),
                queued("k3", "b", ConcernAction.Acknowledge, MutationState.FAILED),
            ),
            NOW,
        )
        val (a, b) = views
        assertEquals(Status.RESOLVED, a.shown.status)
        assertEquals(Status.OPEN, a.server.status)
        assertEquals(1, a.pending)
        assertEquals(Status.OPEN, b.shown.status)
        assertNull(b.shown.acknowledgedAtMs)
        assertEquals(listOf("k2"), b.conflicts.map { it.mutation.idempotencyKey })
        assertEquals(listOf("k3"), b.failed.map { it.mutation.idempotencyKey })
    }

    @Test
    fun anEditThatNoLongerAppliesIsNotShownAsDone() {
        val v = overlay(listOf(concern("a", status = Status.RESOLVED)), listOf(queued("k", "a", ConcernAction.Acknowledge)), NOW).single()
        assertEquals(Status.RESOLVED, v.shown.status)
        assertNull(v.shown.acknowledgedAtMs)
        assertEquals(1, v.pending)
    }

    @Test
    fun anExpiredSnoozeShowsAsOpen() {
        val snoozed = concern("a", status = Status.SNOOZED).copy(snoozeUntilMs = NOW - 1)
        val later = concern("b", status = Status.SNOOZED).copy(snoozeUntilMs = NOW + 1)
        val views = overlay(listOf(snoozed, later), emptyList(), NOW)
        assertEquals(listOf("a"), inbox(views, InboxFilter.ACTIVE).map { it.server.id })
        assertEquals(listOf("b"), inbox(views, InboxFilter.SNOOZED).map { it.server.id })
    }

    @Test
    fun inboxOrdersDecisionsThenImportanceThenDueThenRecency() {
        val views = overlay(
            listOf(
                concern("routine-new", updated = NOW),
                concern("routine-old", updated = NOW - 10_000),
                concern("routine-due", dueAt = NOW + 60_000),
                concern("urgent", Importance.URGENT),
                concern("critical", Importance.CRITICAL),
                concern("conflicted"),
                concern("done", status = Status.RESOLVED),
            ),
            listOf(queued("k", "conflicted", ConcernAction.Resolve, MutationState.CONFLICT)),
            NOW,
        )
        assertEquals(listOf("conflicted", "critical", "urgent", "routine-due", "routine-new", "routine-old"), inbox(views, InboxFilter.ACTIVE).map { it.server.id })
        assertEquals(listOf("done"), inbox(views, InboxFilter.RESOLVED).map { it.server.id })
    }

    @Test
    fun freshnessCountsQueuedAndOwnerDecisionsSeparately() {
        val f = freshness(
            NOW - 60_000,
            false,
            listOf(
                queued("a", "x", ConcernAction.Resolve),
                queued("b", "x", ConcernAction.Resolve, MutationState.HELD),
                queued("c", "y", ConcernAction.Resolve, MutationState.CONFLICT),
            ),
        )
        assertEquals(2, f.queued)
        assertEquals(1, f.needsOwner)
        assertEquals(true, f.held)
        assertEquals(NOW - 60_000, f.lastSyncedAtMs)
    }
}

private class FakeCache(store: InMemoryStore) : CacheSource {
    val concerns = MutableStateFlow(store.concerns.values.toList())
    val mutations = MutableStateFlow(store.queue.toList())
    val state = MutableStateFlow<Pair<Long?, Boolean>>(null to true)
    override fun concerns() = concerns
    override fun mutations() = mutations
    override fun syncState() = state
}

private class CountingScheduler : SyncScheduler {
    var now = 0
    override fun requestNow() {
        now++
    }
    override fun schedulePeriodic() = Unit
    override fun cancelAll() = Unit
}

class ConcernRepositoryTest {
    private val store = InMemoryStore().apply { concerns["a"] = concern("a", revision = 4) }
    private val cache = FakeCache(store)
    private val scheduler = CountingScheduler()
    private var keys = 0
    private val repo = ConcernRepository(cache, store, scheduler, { NOW }, { "key-${++keys}" })

    private fun publish() {
        cache.concerns.value = store.concerns.values.toList()
        cache.mutations.value = store.queue.toList()
    }

    @Test
    fun anActionIsQueuedWithAFreshKeyAndStartsASync() = runTest {
        assertEquals(ActResult.Queued, repo.act("a", ConcernAction.Resolve))
        assertEquals(listOf("key-1"), store.queue.map { it.mutation.idempotencyKey })
        assertEquals(4, store.queue.single().mutation.expectedRevision)
        assertEquals(1, scheduler.now)
        publish()
        val shown = repo.concern("a").first()!!
        assertEquals(Status.RESOLVED, shown.shown.status)
        assertEquals(1, repo.freshness().first().queued)
    }

    @Test
    fun controlsAreCheckedAgainstWhatTheOwnerSees() = runTest {
        repo.act("a", ConcernAction.Resolve)
        publish()
        // Resolved on the phone already, so a second resolve is not offered even though the server copy is open.
        assertEquals(ActResult.NotAvailable, repo.act("a", ConcernAction.Resolve))
        assertEquals(ActResult.Queued, repo.act("a", ConcernAction.Reopen))
        publish()
        assertEquals(ActResult.SnoozeInPast, repo.act("a", ConcernAction.Snooze(NOW - 1)))
        assertEquals(ActResult.NotCached, repo.act("missing", ConcernAction.Resolve))
        assertEquals(listOf("key-1", "key-2"), store.queue.map { it.mutation.idempotencyKey })
    }

    @Test
    fun conflictsAreResolvedOnlyByTheOwner() = runTest {
        repo.act("a", ConcernAction.Acknowledge)
        store.record("key-1", OutboxDecision.Conflict(5), NOW)
        store.concerns["a"] = concern("a", revision = 5)
        publish()
        assertEquals(1, repo.freshness().first().needsOwner)
        repo.reapply("key-1")
        assertEquals(listOf("key-2"), store.queue.map { it.mutation.idempotencyKey })
        assertEquals(5, store.queue.single().mutation.expectedRevision)
        assertEquals(MutationState.QUEUED, store.queue.single().state)
        repo.discard("key-2")
        assertEquals(emptyList<QueuedMutation>(), store.queue)
    }
}
