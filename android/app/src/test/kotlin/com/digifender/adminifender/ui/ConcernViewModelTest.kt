package com.digifender.adminifender.ui

import com.digifender.adminifender.core.data.network.SyncChange
import com.digifender.adminifender.core.data.repository.CacheSource
import com.digifender.adminifender.core.data.repository.ConcernRepository
import com.digifender.adminifender.core.data.repository.SyncScheduler
import com.digifender.adminifender.core.data.sync.LocalStore
import com.digifender.adminifender.core.data.sync.MutationState
import com.digifender.adminifender.core.data.sync.QueuedMutation
import com.digifender.adminifender.core.data.sync.SyncState
import com.digifender.adminifender.core.model.ActionKind
import com.digifender.adminifender.core.model.Concern
import com.digifender.adminifender.core.model.ConcernAction
import com.digifender.adminifender.core.model.Importance
import com.digifender.adminifender.core.model.OutboxDecision
import com.digifender.adminifender.core.model.PendingMutation
import com.digifender.adminifender.core.model.Status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

internal const val NOW = 1_800_000_000_000L

/** Minimal store and cache sharing one queue, so a queued edit shows up in the flows like Room would. */
internal class Fake : LocalStore {
    val concern = MutableStateFlow(listOf(Concern("c1", 2, "Invoice", Status.OPEN, Importance.ROUTINE, null, null, null, null, null, null, null, emptyList(), NOW - 1)))
    val queue = MutableStateFlow(emptyList<QueuedMutation>())
    val cache = object : CacheSource {
        override fun concerns() = concern
        override fun mutations() = queue
        override fun syncState() = MutableStateFlow<Pair<Long?, Boolean>>(NOW to false)
    }
    override suspend fun syncState() = SyncState(1, false, NOW)
    override suspend fun applyChanges(changes: List<SyncChange>, throughSeq: Long, nowMs: Long) = Unit
    override suspend fun replaceWithSnapshot(changes: List<SyncChange>, snapshotSeq: Long, nowMs: Long) = Unit
    override suspend fun markNeedsBootstrap() = Unit
    override suspend fun mutations() = queue.value
    override suspend fun enqueue(key: String, concernId: String, action: ConcernAction, nowMs: Long): PendingMutation {
        val m = PendingMutation(key, concernId, 2 + queue.value.size, action, nowMs)
        queue.value = queue.value + QueuedMutation(m, MutationState.QUEUED, nowMs, null, null)
        return m
    }
    override suspend fun record(key: String, decision: OutboxDecision, nowMs: Long) = Unit
    override suspend fun discard(key: String) = Unit
    override suspend fun reapply(key: String, newKey: String, nowMs: Long): PendingMutation? = null
}

internal object NoSync : SyncScheduler {
    override fun requestNow() = Unit
    override fun schedulePeriodic() = Unit
    override fun cancelAll() = Unit
}

@OptIn(ExperimentalCoroutinesApi::class)
class ConcernViewModelTest {
    private val fake = Fake()
    private var keys = 0
    private val repo = ConcernRepository(fake.cache, fake, NoSync, { NOW }, { "k${++keys}" })

    @Before
    fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun reset() = Dispatchers.resetMain()

    @Test
    fun openingMarksReadOnceAndActionsAreQueuedNotClaimedDone() = runTest {
        val model = ConcernViewModel("c1", repo)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect {} }
        assertEquals(listOf(ActionKind.MARK_READ), fake.queue.value.map { it.mutation.action.kind })
        model.act(ConcernAction.Resolve)
        assertEquals(ActMessage.QUEUED, model.message.value)
        assertEquals(Status.RESOLVED, model.state.value.view?.shown?.status)
        assertEquals(Status.OPEN, model.state.value.view?.server?.status)
        assertEquals(2, model.state.value.view?.pending)
        model.act(ConcernAction.Resolve)
        assertEquals(ActMessage.NOT_AVAILABLE, model.message.value)
        assertEquals(listOf("k1", "k2"), fake.queue.value.map { it.mutation.idempotencyKey })
        job.cancel()
    }

    @Test
    fun waitAndDueDateAreQueuedAndShownOnTopWithoutClaimingTheServerAgreed() = runTest {
        val model = ConcernViewModel("c1", repo)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect {} }
        model.act(ConcernAction.Wait("Asked for terms", "Landlord", NOW + 86_400_000))
        assertEquals(ActMessage.QUEUED, model.message.value)
        val waiting = model.state.value.view!!
        assertEquals(Status.WAITING, waiting.shown.status)
        assertEquals("Landlord", waiting.shown.waiting?.awaitedParty)
        assertEquals(Status.OPEN, waiting.server.status)
        // Editing the wait is allowed while waiting; setting a due date keeps the server revision.
        model.act(ConcernAction.Wait("Chased", "Landlord", null))
        assertEquals(ActMessage.QUEUED, model.message.value)
        model.act(ConcernAction.SetDue(NOW + 3_600_000, "Europe/London", null))
        assertEquals(ActMessage.QUEUED, model.message.value)
        val view = model.state.value.view!!
        assertEquals(NOW + 3_600_000, view.shown.due?.atMs)
        assertEquals(2, view.server.revision)
        assertEquals(listOf(ActionKind.MARK_READ, ActionKind.WAIT, ActionKind.WAIT, ActionKind.SET_DUE), fake.queue.value.map { it.mutation.action.kind })
        assertEquals(listOf("k1", "k2", "k3", "k4"), fake.queue.value.map { it.mutation.idempotencyKey })
        job.cancel()
    }

    @Test
    fun waitIsRefusedLocallyForASnoozedItemButADueDateIsNot() = runTest {
        fake.concern.value = listOf(fake.concern.value.single().copy(status = Status.SNOOZED, snoozeUntilMs = NOW + 60_000))
        val model = ConcernViewModel("c1", repo)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect {} }
        model.act(ConcernAction.Wait(null, null, null))
        assertEquals(ActMessage.NOT_AVAILABLE, model.message.value)
        model.act(ConcernAction.SetDue(NOW + 1, null, null))
        assertEquals(ActMessage.QUEUED, model.message.value)
        job.cancel()
    }

    @Test
    fun aSaveFromAFormOpenedForAnotherConcernIsRefusedNotAppliedHere() = runTest {
        val model = ConcernViewModel("c1", repo)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect {} }
        val queuedBefore = fake.queue.value.size
        // A push opened c1 while a wait form for c2 was still up: its values must not land on c1.
        model.act(ConcernAction.Wait("For the other item", "Someone else", NOW + 86_400_000), forConcern = "c2")
        assertEquals(ActMessage.WRONG_ITEM, model.message.value)
        assertEquals(queuedBefore, fake.queue.value.size)
        assertEquals(Status.OPEN, model.state.value.view?.shown?.status)
        // The form that was opened for this concern still saves.
        model.act(ConcernAction.Wait("Mine", null, null), forConcern = "c1")
        assertEquals(ActMessage.QUEUED, model.message.value)
        assertEquals(Status.WAITING, model.state.value.view?.shown?.status)
        job.cancel()
    }
}
