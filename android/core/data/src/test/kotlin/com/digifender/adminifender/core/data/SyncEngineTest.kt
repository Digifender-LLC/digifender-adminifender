package com.digifender.adminifender.core.data

import com.digifender.adminifender.core.data.network.BootstrapPageDto
import com.digifender.adminifender.core.data.network.ReadResult
import com.digifender.adminifender.core.data.sync.MutationState
import com.digifender.adminifender.core.data.sync.SyncEngine
import com.digifender.adminifender.core.data.sync.SyncOutcome
import com.digifender.adminifender.core.model.ConcernAction
import com.digifender.adminifender.core.model.SendResult
import com.digifender.adminifender.core.model.Status
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncEngineTest {
    private val c1 = "0b4e6f8a-2c3d-4e5f-8a9b-1c2d3e4f5a6b"
    private val c2 = "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"
    private var now = 1_000L
    private val api = FakeApi()
    private val store = InMemoryStore()
    private val engine = SyncEngine(api, store) { now }

    private suspend fun bootstrapped() {
        api.bootstrapPages += ReadResult.Ok(BootstrapPageDto(10, listOf(snapshot(c1, 4), snapshot(c2, 1)), null))
        assertEquals(SyncOutcome.Synced(10, 0, 0), engine.syncOnce())
    }

    @Test
    fun bootstrapsThenFollowsTheFeedPageByPage() = runTest {
        api.bootstrapPages += ReadResult.Ok(BootstrapPageDto(10, listOf(snapshot(c1, 4)), "next"))
        api.bootstrapPages += ReadResult.Ok(BootstrapPageDto(10, listOf(snapshot(c2, 1)), null))
        assertEquals(SyncOutcome.Synced(10, 0, 0), engine.syncOnce())
        assertEquals(listOf("bootstrap:-", "bootstrap:next", "changes:10"), api.log)
        assertEquals(setOf(c1, c2), store.concerns.keys)

        api.changePages += ReadResult.Ok(page(concernChange(11, c1, 5, "resolved"), hasMore = true))
        api.changePages += ReadResult.Ok(page(concernChange(12, c2, 2)))
        assertEquals(SyncOutcome.Synced(12, 0, 0), engine.syncOnce())
        assertEquals(Status.RESOLVED, store.concerns.getValue(c1).status)
        assertEquals(12L, store.state.throughSeq)
    }

    @Test
    fun aPageThatFailsToCommitLeavesTheCursorWhereItWas() = runTest {
        bootstrapped()
        api.changePages += ReadResult.Ok(page(concernChange(11, c1, 5)))
        store.failNextApply = true
        assertThrows { engine.syncOnce() }
        assertEquals(10L, store.state.throughSeq)
        assertEquals(4, store.concerns.getValue(c1).revision)
        api.changePages += ReadResult.Ok(page(concernChange(11, c1, 5)))
        assertEquals(SyncOutcome.Synced(11, 0, 0), engine.syncOnce())
    }

    @Test
    fun anExpiredCursorRebootstrapsAndKeepsQueuedEdits() = runTest {
        bootstrapped()
        store.enqueue("k1", c1, ConcernAction.Acknowledge, now)
        api.actionResults += SendResult.NoAnswer
        api.changePages += ReadResult.CursorExpired
        api.bootstrapPages += ReadResult.Ok(BootstrapPageDto(30, listOf(snapshot(c1, 4)), null))
        assertEquals(SyncOutcome.Synced(30, 0, 0), engine.syncOnce())
        assertEquals(setOf(c1), store.concerns.keys)
        assertEquals(listOf("k1"), store.queue.map { it.mutation.idempotencyKey })
    }

    @Test
    fun resendsUseTheSameKeyAndRespectBackoff() = runTest {
        bootstrapped()
        store.enqueue("k1", c1, ConcernAction.Acknowledge, now)
        api.actionResults += SendResult.NoAnswer
        engine.syncOnce()
        assertEquals(30_000L + now, store.queue.single().nextAttemptAtMs)
        engine.syncOnce()
        assertEquals(listOf("k1"), api.sentKeys)
        now += 30_000
        api.actionResults += SendResult.Accepted(5)
        engine.syncOnce()
        assertEquals(listOf("k1", "k1"), api.sentKeys)
        assertTrue(store.queue.isEmpty())
    }

    @Test
    fun editsToOneConcernGoOutInOrderWithPredictedRevisions() = runTest {
        bootstrapped()
        store.enqueue("k1", c1, ConcernAction.Acknowledge, now)
        store.enqueue("k2", c1, ConcernAction.Snooze(now + 60_000), now)
        store.enqueue("k3", c2, ConcernAction.MarkRead, now)
        api.actionResults += SendResult.NoAnswer
        api.actionResults += SendResult.Accepted(2)
        engine.syncOnce()
        // k2 waits behind k1; c2's edit is independent.
        assertEquals(listOf("action:$c1:4:acknowledge", "action:$c2:1:mark_read"), api.log.filter { it.startsWith("action") })
        now += 30_000
        api.actionResults += SendResult.Accepted(5)
        api.actionResults += SendResult.Accepted(6)
        engine.syncOnce()
        assertEquals("action:$c1:5:snooze", api.log.filter { it.startsWith("action") }.last())
    }

    @Test
    fun aStaleEditBecomesAConflictAndBlocksLaterEditsUntilTheOwnerDecides() = runTest {
        bootstrapped()
        store.enqueue("k1", c1, ConcernAction.Resolve, now)
        store.enqueue("k2", c1, ConcernAction.MarkRead, now)
        api.actionResults += SendResult.StaleRevision(7)
        api.changePages += ReadResult.Ok(page(concernChange(11, c1, 7, "snoozed")))
        assertEquals(SyncOutcome.Synced(11, 0, 1), engine.syncOnce())
        assertEquals(listOf(MutationState.CONFLICT, MutationState.QUEUED), store.queue.map { it.state })
        assertEquals(7, store.queue.first().currentRevision)
        // The server's newer decision is what the store shows.
        assertEquals(Status.SNOOZED, store.concerns.getValue(c1).status)
        assertEquals(1, api.sentKeys.size)

        // Explicit reapplication sends the intent again against the current revision, under a new key.
        store.discard("k2")
        store.reapply("k1", "k1b", now)
        api.actionResults += SendResult.Accepted(8)
        engine.syncOnce()
        assertEquals("action:$c1:7:resolve", api.log.filter { it.startsWith("action") }.last())
        assertEquals(listOf("k1", "k1b"), api.sentKeys)
    }

    @Test
    fun anExpiredSessionHoldsTheQueueAndStopsTheSync() = runTest {
        bootstrapped()
        store.enqueue("k1", c1, ConcernAction.Acknowledge, now)
        api.actionResults += SendResult.SessionExpired
        assertEquals(SyncOutcome.SignInRequired, engine.syncOnce())
        assertEquals(MutationState.HELD, store.queue.single().state)
        assertTrue(api.log.none { it.startsWith("changes:") && it != "changes:10" })
        api.actionResults += SendResult.Rejected("invalid_transition")
        engine.syncOnce()
        assertEquals(MutationState.FAILED, store.queue.single().state)
        assertEquals("invalid_transition", store.queue.single().errorCode)
    }

    @Test
    fun anEditForAnUnknownConcernIsNotQueued() = runTest {
        bootstrapped()
        assertNull(store.enqueue("k1", "missing", ConcernAction.Acknowledge, now))
    }

    private suspend fun assertThrows(block: suspend () -> Unit) {
        var thrown = false
        try {
            block()
        } catch (_: IllegalStateException) {
            thrown = true
        }
        assertTrue(thrown)
    }
}
