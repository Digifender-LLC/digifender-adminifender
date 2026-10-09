package com.digifender.adminifender.core.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.digifender.adminifender.core.data.export.ExportDownload
import com.digifender.adminifender.core.data.export.ExportIntent
import com.digifender.adminifender.core.data.export.ExportPhase
import com.digifender.adminifender.core.data.export.ExportVerification
import com.digifender.adminifender.core.data.files.BlobDirectory
import com.digifender.adminifender.core.data.files.CommitResult
import com.digifender.adminifender.core.data.files.FileRequest
import com.digifender.adminifender.core.data.files.FileState
import com.digifender.adminifender.core.data.files.RequestResult
import com.digifender.adminifender.core.data.local.AppDatabase
import com.digifender.adminifender.core.data.local.FilesDropped
import com.digifender.adminifender.core.data.local.RoomArchiveCache
import com.digifender.adminifender.core.data.local.RoomChatCache
import com.digifender.adminifender.core.data.local.RoomDeletedSourcePurge
import com.digifender.adminifender.core.data.local.RoomDeletionStore
import com.digifender.adminifender.core.data.local.RoomExportStore
import com.digifender.adminifender.core.data.local.RoomFileStore
import com.digifender.adminifender.core.data.local.RoomLocalStore
import com.digifender.adminifender.core.data.local.toEntity
import com.digifender.adminifender.core.data.network.Cleanup
import com.digifender.adminifender.core.data.network.CleanupState
import com.digifender.adminifender.core.data.network.ExportAdmission
import com.digifender.adminifender.core.data.network.ExportCounts
import com.digifender.adminifender.core.data.network.ExportFilter
import com.digifender.adminifender.core.data.network.ExportScope
import com.digifender.adminifender.core.data.network.ExportState
import com.digifender.adminifender.core.data.network.ExportStatus
import com.digifender.adminifender.core.data.network.Omission
import com.digifender.adminifender.core.data.network.OmissionReason
import com.digifender.adminifender.core.data.network.OperationReceipt
import com.digifender.adminifender.core.data.network.OperationState
import com.digifender.adminifender.core.data.network.ReviewState
import com.digifender.adminifender.core.data.network.SourceSummary
import com.digifender.adminifender.core.data.network.SyncChange
import com.digifender.adminifender.core.data.review.DeletionPhase
import com.digifender.adminifender.core.data.review.DeletionReview
import com.digifender.adminifender.core.data.review.StoredPreview
import com.digifender.adminifender.core.data.review.StoredReadback
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val SRC = "0b4e6f8a-2c3d-4e5f-8a9b-1c2d3e4f5a6b"
private const val ATT = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
private const val ATT2 = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
private val SHA = "a".repeat(64)

/**
 * Room and file-system behaviour of slice 14i on a device or emulator: version 4 to 5 migration, the transactional file
 * store (generation, commit, eviction, restriction), what sync does to downloaded files, the deleted-email guard and
 * the stores of deletion reviews and export intents. Compiled by CI; **not run** anywhere yet (no emulator is available here).
 */
@RunWith(AndroidJUnit4::class)
class RoomFilesReviewsTest {
    @get:Rule
    val migrations = migrationHelper()

    private lateinit var db: AppDatabase
    private lateinit var blobs: BlobDirectory
    private lateinit var files: RoomFileStore
    private val dropped = mutableListOf<List<String>>()

    private fun source(id: String = SRC, revision: Int = 1, quarantined: Boolean = false, privacy: String = "hybrid") =
        SourceSummary(id, revision, "ready", quarantined, privacy, 1_000, "Subject", listOf("a@example.com"))

    @Before
    fun open() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        blobs = BlobDirectory(File(context.cacheDir, "blobs-test-${System.nanoTime()}"))
        files = RoomFileStore(db)
    }

    @After
    fun close() {
        db.close()
        blobs.deleteAll()
    }

    private fun store() = RoomLocalStore(db, blobs, FilesDropped { dropped += it })

    private suspend fun ready(id: String, blob: String, gen: String = "g-$id") {
        files.request(FileRequest(id, SRC, "a.txt", "text/plain", 10, SHA), gen, 1)
        files.claim(id, gen, 2)
        files.commit(id, gen, blob, 1_000_000, 3)
    }

    @Test
    fun version4MigratesToVersion5KeepingEditsDraftsAndQuestions() {
        migrations.createDatabase("migration-test-5", 4).use { old ->
            old.execSQL("INSERT INTO sync_state(id, throughSeq, needsBootstrap, lastSyncedAt) VALUES (0, 9, 0, 100)")
            old.execSQL("INSERT INTO sources(id, revision, state, quarantined, privacy, receivedAt, subject, fromJson) VALUES ('$SRC', 2, 'ready', 0, 'hybrid', 5, 'Bill', '[]')")
            old.execSQL("INSERT INTO source_texts(sourceId, revision, text, textComplete, attachmentsJson, cachedAt) VALUES ('$SRC', 2, 'hello', 1, '[{\"id\":\"$ATT\",\"name\":\"a.pdf\",\"sizeBytes\":7,\"extraction\":\"stored\"}]', 6)")
            old.execSQL("INSERT INTO pending_mutations(idempotencyKey, concernId, expectedRevision, kind, argsJson, createdAt, attempts, state, nextAttemptAt, errorCode, currentRevision) VALUES ('k1', 'c1', 3, 'acknowledge', '{}', 7, 0, 'QUEUED', 7, NULL, NULL)")
            old.execSQL("INSERT INTO chat_threads(localId, createKey, serverId, scopeKind, scopeId, createState, createError, draft, olderCursor, historyLoaded, createdAt, updatedAt) VALUES ('t1', 'ck', NULL, 'all', NULL, 'PENDING', NULL, 'unsent words', NULL, 0, 8, 8)")
            old.execSQL("INSERT INTO chat_turns(turnId, threadLocalId, content, scopeKind, scopeId, dispatch, dispatchError, state, reason, errorCode, answerJson, serverInvalidated, serverWithheld, localInvalid, cancelNote, createdAt, completedAt, checkedAt) VALUES ('turn1', 't1', 'a question', 'all', NULL, 'QUEUED', NULL, NULL, NULL, NULL, NULL, 0, 0, NULL, NULL, 9, NULL, NULL)")
        }
        migrations.runMigrationsAndValidate("migration-test-5", 5, true).use { migrated ->
            fun count(table: String) = migrated.query("SELECT COUNT(*) FROM $table").use { c -> c.moveToFirst(); c.getInt(0) }
            assertEquals(1, count("pending_mutations"))
            assertEquals(1, count("chat_turns"))
            assertEquals(1, count("source_texts"))
            migrated.query("SELECT draft FROM chat_threads WHERE localId = 't1'").use { c -> c.moveToFirst(); assertEquals("unsent words", c.getString(0)) }
            migrated.query("SELECT dispatch FROM chat_turns WHERE turnId = 'turn1'").use { c -> c.moveToFirst(); assertEquals("QUEUED", c.getString(0)) }
            // Nothing is asked of the owner or the server by the migration itself.
            migrated.query("SELECT throughSeq, needsBootstrap FROM sync_state").use { c -> c.moveToFirst(); assertEquals(9, c.getInt(0)); assertEquals(0, c.getInt(1)) }
            for (table in listOf("local_files", "source_tombstones", "deletion_reviews", "export_intents")) assertEquals(table, 0, count(table))
        }
    }

    @Test
    fun anInventoryCachedBeforeVersion5HasNoChecksumAndIsNeverDownloadedUnverified() = runTest {
        db.sources().upsert(listOf(source().toEntity()))
        db.openHelper.writableDatabase.execSQL("INSERT INTO source_texts(sourceId, revision, text, textComplete, attachmentsJson, cachedAt) VALUES ('$SRC', 1, 't', 1, '[{\"id\":\"$ATT\",\"name\":\"a.pdf\",\"sizeBytes\":7,\"extraction\":\"stored\"}]', 6)")
        val text = RoomArchiveCache(db).text(SRC).first()!!
        assertEquals("", text.attachments.single().sha256)
    }

    @Test
    fun theFileStoreFencesEveryStepByGenerationAndCommitsAgainstTheSourceTable() = runTest {
        db.sources().upsert(listOf(source().toEntity()))
        val asked = files.request(FileRequest(ATT, SRC, "a.txt", "text/plain", 10, SHA), "g1", 1) as RequestResult.Requested
        assertEquals(FileState.QUEUED, asked.file.state)
        assertNull(files.claim(ATT, "other", 2))
        assertNotNull(files.claim(ATT, "g1", 2))
        // A restart under a new generation (after a purge) cannot be finished by the old worker.
        files.remove(ATT, null)
        files.request(FileRequest(ATT, SRC, "a.txt", "text/plain", 10, SHA), "g2", 3)
        assertEquals(CommitResult.Superseded, files.commit(ATT, "g1", "0".repeat(32) + ".enc", 1_000_000, 4))
        assertNotNull(files.claim(ATT, "g2", 4))
        // The email is quarantined before the commit: the copy is refused and the row removed.
        db.sources().upsert(listOf(source(quarantined = true).toEntity()))
        assertEquals(CommitResult.Superseded, files.commit(ATT, "g2", "1".repeat(32) + ".enc", 1_000_000, 5))
        assertNull(files.get(ATT))
    }

    @Test
    fun theLeastRecentlyOpenedCopyIsEvictedToStayUnderTheCap() = runTest {
        db.sources().upsert(listOf(source().toEntity()))
        for ((i, id) in listOf(ATT, ATT2).withIndex()) {
            files.request(FileRequest(id, SRC, "$id.txt", "text/plain", 600, SHA), "g$i", 1L + i)
            files.claim(id, "g$i", 2L + i)
        }
        val first = files.commit(ATT, "g0", "2".repeat(32) + ".enc", 1_000, 10) as CommitResult.Committed
        assertTrue(first.evicted.isEmpty())
        val second = files.commit(ATT2, "g1", "3".repeat(32) + ".enc", 1_000, 20) as CommitResult.Committed
        assertEquals(listOf("2".repeat(32) + ".enc"), second.evicted)
        assertNull(files.get(ATT))
    }

    @Test
    fun syncRemovingARestrictedOrDeletedEmailDropsItsFilesAndTellsTheAppToStopTheDownloads() = runTest {
        db.sources().upsert(listOf(source().toEntity(), source("1b4e6f8a-2c3d-4e5f-8a9b-1c2d3e4f5a6b").toEntity()))
        val blob = "4".repeat(32) + ".enc"
        File(blobs.file(blob)!!.path).writeText("sealed")
        ready(ATT, blob)
        files.request(FileRequest(ATT2, SRC, "b.txt", "text/plain", 10, SHA), "gb", 1)
        files.claim(ATT2, "gb", 2)
        // Quarantine: the ready copy and the running download both go.
        store().applyChanges(listOf(SyncChange.SourceChanged(1, source(revision = 2, quarantined = true))), 1, 100)
        assertNull(files.get(ATT))
        assertNull(files.get(ATT2))
        assertFalse(blobs.exists(blob))
        assertEquals(listOf(setOf(ATT, ATT2)), dropped.map { it.toSet() })
        // An ordinary new revision of an unrestricted email keeps its copy.
        db.sources().upsert(listOf(source(revision = 3).toEntity()))
        ready(ATT, "5".repeat(32) + ".enc", "g9")
        store().applyChanges(listOf(SyncChange.SourceChanged(2, source(revision = 4))), 2, 200)
        assertNotNull(files.get(ATT))
        // Deletion by tombstone.
        store().applyChanges(listOf(SyncChange.Removed(3, "source", SRC)), 3, 300)
        assertNull(files.get(ATT))
    }

    @Test
    fun aDeletedEmailStaysGoneWhenAStalePageStillListsIt() = runTest {
        db.sources().upsert(listOf(source().toEntity()))
        val chat = RoomChatCache(db)
        RoomDeletedSourcePurge(db, chat, blobs).purge(listOf(SRC), 50)
        assertNull(db.sources().get(SRC))
        store().applyChanges(listOf(SyncChange.SourceChanged(1, source(revision = 2))), 1, 100)
        assertNull("a stale page cannot bring the deleted email back", db.sources().get(SRC))
        // The tombstone arrives: the guard has done its work.
        store().applyChanges(listOf(SyncChange.Removed(2, "source", SRC)), 2, 200)
        assertTrue(db.tombstones().ids().isEmpty())
    }

    @Test
    fun deletionReviewsAndExportIntentsSurviveTheRoundTripThroughRoom() = runTest {
        val review = DeletionReview(
            "d1", listOf(SRC), "pk", DeletionPhase.SENDING, "network", "r1", "d".repeat(64), 99, mapOf(SRC to 2), mapOf("c1" to 3),
            StoredPreview(2, listOf("e1"), listOf("e2"), listOf("search index"), 1), "ck", "op1", OperationReceipt.Deletion(7, "b".repeat(64), 1),
            Cleanup(CleanupState.PARTIAL, 2, 1, 0, 0), 55, StoredReadback(ReviewState.CONSUMED, OperationState.SUCCEEDED, 66), 1, 2,
        )
        val deletions = RoomDeletionStore(db)
        deletions.put(review)
        assertEquals(review, deletions.get("d1"))
        deletions.delete("d1")
        assertNull(deletions.get("d1"))

        val scope = ExportScope.Filtered(ExportAdmission.QUARANTINE, ExportFilter(1_000, 2_000, "a@example.com", null))
        val status = ExportStatus("9d8c7b6a-5f4e-4d3c-b2a1-0f9e8d7c6b5a", ExportState.READY, scope, 41, 1, 2, ExportCounts(1, 0, 0, 0, 0), listOf(Omission(SRC, OmissionReason.DELETED)), null, null)
        val intent = ExportIntent(
            "e1", "key", scope, ExportPhase.CREATED, null, status.id, status, 5, ExportDownload.VERIFIED, null, "export.zip", null,
            ExportVerification(8, 1234, "c".repeat(64), 7, 1, 0, false, false), 6, 1, 2,
        )
        val exports = RoomExportStore(db)
        exports.put(intent)
        assertEquals(intent, exports.get("e1"))
        assertEquals(listOf(intent), exports.observeAll().first())
    }
}
