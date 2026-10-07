package com.digifender.adminifender.core.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.digifender.adminifender.core.data.local.AppDatabase
import com.digifender.adminifender.core.data.local.LocalCache
import com.digifender.adminifender.core.data.local.RoomLocalStore
import com.digifender.adminifender.core.data.network.SourceSummary
import com.digifender.adminifender.core.data.network.SyncChange
import com.digifender.adminifender.core.data.repository.SourceText
import com.digifender.adminifender.core.data.sync.MutationState
import com.digifender.adminifender.core.model.Concern
import com.digifender.adminifender.core.model.ConcernAction
import com.digifender.adminifender.core.model.Importance
import com.digifender.adminifender.core.model.OutboxDecision
import com.digifender.adminifender.core.model.Status
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomDeletionFenceTest {
    private lateinit var db: AppDatabase
    private lateinit var cache: LocalCache
    private lateinit var store: RoomLocalStore
    @get:Rule val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    private fun source(id: String) = SourceSummary(id, 1, "ready", false, "hybrid", 10, "Deleted private title", emptyList())
    private fun concern(id: String, revision: Int, ids: List<String>) = Concern(id, revision, "Private concern title", Status.OPEN, Importance.ROUTINE,
        null, null, null, null, null, null, null, ids, 10)

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        cache = LocalCache(db)
        store = RoomLocalStore(db)
    }
    @After fun close() = db.close()

    @Test fun deletionFencesStalePagesAndSnapshotsWithoutLosingUnrelatedIntentOrAdvancingCursor() = runTest {
        val stale = listOf(SyncChange.SourceChanged(1, source("deleted")), SyncChange.ConcernChanged(2, concern("affected", 4, listOf("deleted", "retained"))))
        store.applyChanges(stale + SyncChange.ConcernChanged(3, concern("unrelated", 2, listOf("retained"))), 3, 20)
        cache.archive.putText("deleted", SourceText(1, "Private body", true, emptyList()), 20)
        store.enqueue("affected-edit", "affected", ConcernAction.Acknowledge, 21)
        store.enqueue("unrelated-edit", "unrelated", ConcernAction.Resolve, 22)
        val before = store.syncState()

        cache.invalidateSources(listOf("deleted"), mapOf("affected" to 5))
        assertEquals(before, store.syncState())
        assertNull(db.sources().revision("deleted"))
        assertNull(cache.archive.text("deleted").first())
        assertNull(db.concerns().get("affected"))
        assertEquals(listOf(MutationState.CONFLICT, MutationState.QUEUED), store.mutations().map { it.state })
        assertEquals("source_deleted", store.mutations().first().errorCode)
        assertNull(store.reapply("affected-edit", "new-edit", 30))
        assertEquals(2, store.mutations().size)
        store.record("affected-edit", OutboxDecision.HoldForSignIn, 30)
        assertEquals(MutationState.CONFLICT, store.mutations().first().state)

        store.applyChanges(stale, 4, 40)
        assertNull(db.sources().revision("deleted"))
        assertNull(db.concerns().get("affected"))
        store.replaceWithSnapshot(stale, 5, 50)
        assertNull(db.sources().revision("deleted"))
        assertNull(db.concerns().get("affected"))
        // Even a higher concern revision cannot reintroduce a deleted source association.
        store.applyChanges(listOf(SyncChange.ConcernChanged(6, concern("affected", 6, listOf("deleted")))), 6, 60)
        assertNull(db.concerns().get("affected"))
        store.applyChanges(listOf(SyncChange.ConcernChanged(7, concern("affected", 6, listOf("retained")).copy(title = "Retained concern"))), 7, 70)
        assertEquals("Retained concern", db.concerns().get("affected")?.title)
        assertNotNull(store.reapply("affected-edit", "reviewed-edit", 80))
        assertEquals(setOf("reviewed-edit", "unrelated-edit"), store.mutations().map { it.mutation.idempotencyKey }.toSet())
    }

    @Test fun remoteTombstonesAlsoFenceAnOlderResponseAndClearRemovesFences() = runTest {
        store.applyChanges(listOf(SyncChange.SourceChanged(1, source("deleted"))), 1, 10)
        store.applyChanges(listOf(SyncChange.Removed(2, "source", "deleted")), 2, 20)
        store.applyChanges(listOf(SyncChange.SourceChanged(1, source("deleted"))), 2, 30)
        assertNull(db.sources().revision("deleted"))
        cache.clear()
        assertEquals(emptyList<String>(), db.deletionFences().suppressedIds())
    }

    @Test fun confirmationAfterSyncKeepsTheAlreadySanitizedNewerConcern() = runTest {
        store.applyChanges(listOf(SyncChange.ConcernChanged(8, concern("detached", 6, listOf("retained")).copy(title = "Retained title"))), 8, 80)
        cache.invalidateSources(listOf("deleted"), mapOf("detached" to 5))
        assertEquals("Retained title", db.concerns().get("detached")?.title)
        assertEquals(8L, store.syncState().throughSeq)
    }

    @Test fun version2MigrationPreservesPendingEditsAndStartsWithEmptyFences() {
        migrations.createDatabase("deletion-migration", 2).use { old ->
            old.execSQL("INSERT INTO pending_mutations VALUES ('edit', 'concern', 4, 'acknowledge', '{}', 10, 0, 'QUEUED', 10, NULL, NULL)")
            old.execSQL("INSERT INTO sync_state VALUES (0, 42, 0, 10)")
        }
        migrations.runMigrationsAndValidate("deletion-migration", 3, true).use { migrated ->
            migrated.query("SELECT idempotencyKey FROM pending_mutations").use { assertTrue(it.moveToFirst()); assertEquals("edit", it.getString(0)) }
            migrated.query("SELECT throughSeq FROM sync_state").use { assertTrue(it.moveToFirst()); assertEquals(42L, it.getLong(0)) }
            migrated.query("SELECT COUNT(*) FROM suppressed_sources").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            migrated.query("SELECT COUNT(*) FROM concern_deletion_fences").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
        }
    }
}
