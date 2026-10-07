package com.digifender.adminifender.core.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.digifender.adminifender.core.data.local.AppDatabase
import com.digifender.adminifender.core.data.local.RoomLocalStore
import com.digifender.adminifender.core.data.network.SyncChange
import com.digifender.adminifender.core.data.sync.MutationState
import com.digifender.adminifender.core.model.Concern
import com.digifender.adminifender.core.model.ConcernAction
import com.digifender.adminifender.core.model.Importance
import com.digifender.adminifender.core.model.OutboxDecision
import com.digifender.adminifender.core.model.Status
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Room-backed store semantics on a device or emulator (not run in CI until an emulator runner exists). */
@RunWith(AndroidJUnit4::class)
class RoomLocalStoreTest {
    private lateinit var db: AppDatabase
    private lateinit var store: RoomLocalStore

    private fun concern(id: String, revision: Int) = Concern(id, revision, "t", Status.OPEN, Importance.ROUTINE, null, null, null, null, null, null, null, emptyList(), 0)

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        store = RoomLocalStore(db)
    }

    @After
    fun close() = db.close()

    @Test
    fun staleFormRevisionCannotQueueAgainstANewerCacheRow() = runTest {
        store.applyChanges(listOf(SyncChange.ConcernChanged(1, concern("a", 4))), 1, 10)
        store.applyChanges(listOf(SyncChange.ConcernChanged(2, concern("a", 5))), 2, 20)
        assertEquals(null, store.enqueueIfRevision("stale", "a", 4, ConcernAction.SetDue(30, "UTC", null), 30))
        assertEquals(emptyList<Any>(), store.mutations())
        assertEquals(5, store.enqueueIfRevision("fresh", "a", 5, ConcernAction.SetDue(30, "UTC", null), 30)?.expectedRevision)
    }

    @Test
    fun snapshotReplacesCacheButKeepsQueuedEdits() = runTest {
        store.applyChanges(listOf(SyncChange.ConcernChanged(1, concern("a", 4)), SyncChange.ConcernChanged(2, concern("b", 1))), 2, 10)
        store.enqueue("k1", "a", ConcernAction.Acknowledge, 10)
        store.enqueue("k2", "a", ConcernAction.MarkRead, 10)
        assertEquals(listOf(4, 5), store.mutations().map { it.mutation.expectedRevision })
        store.replaceWithSnapshot(listOf(SyncChange.ConcernChanged(5, concern("a", 4))), 5, 20)
        assertEquals(null, db.concerns().get("b"))
        assertEquals(listOf("k1", "k2"), store.mutations().map { it.mutation.idempotencyKey })
        store.record("k1", OutboxDecision.Conflict(6), 30)
        assertEquals(MutationState.CONFLICT, store.mutations().first().state)
        assertEquals(5L, store.syncState().throughSeq)
    }
}
