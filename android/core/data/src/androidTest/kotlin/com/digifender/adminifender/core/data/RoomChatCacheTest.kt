package com.digifender.adminifender.core.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.digifender.adminifender.core.data.local.AppDatabase
import com.digifender.adminifender.core.data.local.ChatTurnEntity
import com.digifender.adminifender.core.data.local.RoomChatCache
import com.digifender.adminifender.core.data.local.RoomLocalStore
import com.digifender.adminifender.core.data.network.ChatScope
import com.digifender.adminifender.core.data.network.ScopeKind
import com.digifender.adminifender.core.data.network.SourceSummary
import com.digifender.adminifender.core.data.network.SyncChange
import com.digifender.adminifender.core.data.repository.ChatAnswer
import com.digifender.adminifender.core.data.repository.ChatCitation
import com.digifender.adminifender.core.data.repository.ChatClaim
import com.digifender.adminifender.core.data.repository.ChatThread
import com.digifender.adminifender.core.data.repository.ChatTurn
import com.digifender.adminifender.core.data.repository.ClaimBasis
import com.digifender.adminifender.core.data.repository.ContextSource
import com.digifender.adminifender.core.data.repository.CreateState
import com.digifender.adminifender.core.data.repository.Dispatch
import com.digifender.adminifender.core.data.repository.EditAgain
import com.digifender.adminifender.core.data.repository.LocalInvalid
import com.digifender.adminifender.core.data.repository.PageKind
import com.digifender.adminifender.core.data.repository.ServerState
import com.digifender.adminifender.core.data.repository.ServerTurn
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

/**
 * Chat store on Room, driven by real sync writes. Written for a device or emulator; like the other instrumentation
 * tests it is compiled by the build but has not been run on one yet.
 */
@RunWith(AndroidJUnit4::class)
class RoomChatCacheTest {
    private lateinit var db: AppDatabase
    private lateinit var store: RoomLocalStore
    private lateinit var cache: RoomChatCache

    @get:Rule
    val migrations = migrationHelper()

    private val serverThread = "7c1d2e3f-4a5b-4c6d-8e7f-9a0b1c2d3e4f"
    private val turnA = "11111111-1111-4111-8111-111111111111"
    private val turnB = "22222222-2222-4222-8222-222222222222"
    private val s1 = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private val s2 = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

    private fun source(id: String, revision: Int = 1, quarantined: Boolean = false, privacy: String = "hybrid", at: Long = 100) =
        SourceSummary(id, revision, "ready", quarantined, privacy, at, "Water bill", listOf("billing@water.example"))

    private fun thread(localId: String = "t1", scope: ChatScope = ChatScope(ScopeKind.ALL), draft: String = "") =
        ChatThread(localId, "key-$localId", serverThread, scope, CreateState.CREATED, null, draft, null, false, 1, 1)

    /** Cites [sourceId]; [also] are emails the model was given and did not cite. */
    private fun answer(sourceId: String, vararg also: String) = ChatAnswer(
        "Paid on Friday.",
        listOf(ChatClaim("The invoice was paid", ClaimBasis.EVIDENCE, listOf(ChatCitation("chunk-1", sourceId, null, null, 0, 4, "Paid")))),
        listOf("No receipt seen"),
        emptyList(),
        (listOf(sourceId) + also).map { ContextSource(it, "hybrid") },
    )

    private fun turn(id: String = turnA, threadId: String = "t1", dispatch: Dispatch = Dispatch.QUEUED, state: ServerState? = null, answer: ChatAnswer? = null, completedAt: Long? = null) =
        ChatTurn(id, threadId, "Did I pay?", ChatScope(ScopeKind.ALL), dispatch, null, state, null, null, answer, false, false, null, null, 10, completedAt, null)

    private fun server(id: String = turnA, answer: ChatAnswer? = null, state: ServerState = ServerState.COMPLETE) =
        ServerTurn(id, serverThread, ChatScope(ScopeKind.ALL), "Did I pay?", state, null, null, answer, null, false, false, 10, 50)

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        store = RoomLocalStore(db)
        cache = RoomChatCache(db)
    }

    @After
    fun close() = db.close()

    private suspend fun completeWith(sourceId: String, vararg also: String, scope: ChatScope = ChatScope(ScopeKind.ALL), threadId: String = "t1") {
        cache.insertThread(thread(threadId, scope))
        cache.submit(turn(threadId = threadId), 11)
        cache.markAttempted(turnA)
        assertTrue(cache.applyServer(threadId, listOf(server(answer = answer(sourceId, *also))), PageKind.Single, false, 60))
    }

    @Test
    fun submitStoresTheExactTurnAndEmptiesTheDraftTogether() = runTest {
        cache.insertThread(thread(draft = "Did I pay?"))
        assertTrue(cache.submit(turn(), 20))
        assertEquals("", cache.threadOnce("t1")!!.draft)
        val stored = cache.turnOnce(turnA)!!
        assertEquals("Did I pay?", stored.content)
        assertEquals(Dispatch.QUEUED, stored.dispatch)
        assertFalse(cache.submit(turn(id = turnB, threadId = "gone"), 20))
        assertNull(cache.turnOnce(turnB))
    }

    @Test
    fun theAttemptIsWrittenOnlyFromQueued() = runTest {
        cache.insertThread(thread())
        cache.submit(turn(), 20)
        assertTrue(cache.markAttempted(turnA))
        assertFalse(cache.markAttempted(turnA))
        assertEquals(Dispatch.ATTEMPTED, cache.turnOnce(turnA)!!.dispatch)
    }

    @Test
    fun aSyncedDeletionRemovesTheAnswerForGoodButKeepsTheQuestion() = runTest {
        store.applyChanges(listOf(SyncChange.SourceChanged(1, source(s1))), 1, 5)
        completeWith(s1)
        assertNotNull(cache.turnOnce(turnA)!!.answer)

        store.applyChanges(listOf(SyncChange.Removed(2, "source", s1)), 2, 70)
        val after = cache.turnOnce(turnA)!!
        assertNull(after.answer)
        assertEquals(LocalInvalid.SOURCE_CHANGED, after.localInvalid)
        assertEquals("Did I pay?", after.content)

        // A late read that still carries the answer cannot bring it back.
        cache.applyServer("t1", listOf(server(answer = answer(s1))), PageKind.Single, false, 80)
        assertNull(cache.turnOnce(turnA)!!.answer)
        assertEquals(LocalInvalid.SOURCE_CHANGED, cache.turnOnce(turnA)!!.localInvalid)
    }

    @Test
    fun quarantineAndAStricterPolicyInvalidateButAnOrdinaryRevisionDoesNot() = runTest {
        store.applyChanges(listOf(SyncChange.SourceChanged(1, source(s1))), 1, 5)
        completeWith(s1)
        store.applyChanges(listOf(SyncChange.SourceChanged(2, source(s1, revision = 2))), 2, 70)
        assertNotNull(cache.turnOnce(turnA)!!.answer)

        store.applyChanges(listOf(SyncChange.SourceChanged(3, source(s1, revision = 3, privacy = "no_ai"))), 3, 80)
        assertNull(cache.turnOnce(turnA)!!.answer)
    }

    @Test
    fun anEmailTheAnswerWasGivenButDidNotCiteDecidesToo() = runTest {
        store.applyChanges(listOf(SyncChange.SourceChanged(1, source(s1)), SyncChange.SourceChanged(2, source(s2))), 2, 5)
        completeWith(s1, s2)
        // Mail unrelated to the answer, however it arrives, changes nothing.
        store.applyChanges(listOf(SyncChange.SourceChanged(3, source("cccccccc-cccc-4ccc-8ccc-cccccccccccc", quarantined = true, at = 1))), 3, 70)
        assertNotNull(cache.turnOnce(turnA)!!.answer)
        store.applyChanges(listOf(SyncChange.SourceChanged(4, source(s2, revision = 2, quarantined = true))), 4, 80)
        assertNull(cache.turnOnce(turnA)!!.answer)
        assertEquals(LocalInvalid.SOURCE_CHANGED, cache.turnOnce(turnA)!!.localInvalid)
    }

    @Test
    fun aQuestionWithoutAnAnswerIsNeverMarkedByASyncedChange() = runTest {
        store.applyChanges(listOf(SyncChange.SourceChanged(1, source(s1))), 1, 5)
        cache.insertThread(thread())
        cache.submit(turn(), 20)
        store.applyChanges(listOf(SyncChange.Removed(2, "source", s1)), 2, 70)
        val queued = cache.turnOnce(turnA)!!
        assertNull(queued.localInvalid)
        assertEquals(Dispatch.QUEUED, queued.dispatch)
    }

    @Test
    fun aSnapshotThatNoLongerListsAnEmailInvalidatesAndNewSpamDoesNot() = runTest {
        store.applyChanges(listOf(SyncChange.SourceChanged(1, source(s1))), 1, 5)
        completeWith(s1)
        // Quarantined mail the snapshot adds is no part of the answer, whenever it was received.
        store.replaceWithSnapshot(listOf(SyncChange.SourceChanged(2, source(s1)), SyncChange.SourceChanged(2, source(s2, quarantined = true, at = 5))), 2, 90)
        assertNotNull(cache.turnOnce(turnA)!!.answer)
        store.replaceWithSnapshot(listOf(SyncChange.SourceChanged(3, source(s2, quarantined = true, at = 500))), 3, 95)
        assertNull(cache.turnOnce(turnA)!!.answer)
    }

    @Test
    fun editingAgainMovesTheTextToTheDraftInOneStepAndNeverReplacesOtherText() = runTest {
        cache.insertThread(thread())
        cache.submit(turn(), 20)
        cache.setDispatch(turnA, Dispatch.REFUSED, "invalid_request", setOf(Dispatch.QUEUED))
        cache.setDraft("t1", "other text")
        assertEquals(EditAgain.DraftInUse, cache.editAgain(turnA))
        assertNotNull(cache.turnOnce(turnA))
        cache.setDraft("t1", "")
        assertEquals(EditAgain.Restored("Did I pay?"), cache.editAgain(turnA))
        assertNull(cache.turnOnce(turnA))
        assertEquals("Did I pay?", cache.threadOnce("t1")!!.draft)
    }

    @Test
    fun whatSignOutWouldDiscardIsCounted() = runTest {
        cache.insertThread(thread(draft = "typing"))
        cache.submit(turn(), 20)
        // submit empties the draft; type again.
        cache.setDraft("t1", "typing")
        val unsent = cache.unsent().first()
        assertEquals(1, unsent.unconfirmedQuestions)
        assertEquals(1, unsent.drafts)
        assertEquals(0, unsent.atWorker)
    }

    @Test
    fun anAnswerStoredWithoutItsContextIsHeldBackAndReadAgain() = runTest {
        store.applyChanges(listOf(SyncChange.SourceChanged(1, source(s1))), 1, 5)
        completeWith(s1)
        // The shape an older build stored: an answer with no list of the emails it rests on.
        db.openHelper.writableDatabase.execSQL("UPDATE chat_turns SET answerJson = '{\"text\":\"Paid.\"}' WHERE turnId = '$turnA'")
        val damaged = cache.turnOnce(turnA)!!
        assertNull(damaged.answer)
        assertEquals(LocalInvalid.UNVERIFIED, damaged.localInvalid)
        assertEquals(listOf(turnA), cache.needingCheck().map { it.turnId })
        // Reading it again from the Worker restores a verifiable answer and clears the damage.
        cache.applyServer("t1", listOf(server(answer = answer(s1))), PageKind.Single, false, 90)
        assertNotNull(cache.turnOnce(turnA)!!.answer)
        assertTrue(cache.needingCheck().isEmpty())
    }

    private suspend fun storeRaw(id: String, answerJson: String?) = db.chat().insertTurn(
        ChatTurnEntity(id, "t1", "Did I pay?", "all", null, "ACCEPTED", null, "COMPLETE", null, null, answerJson, false, false, null, null, 10, 50, null),
    )

    @Test
    fun storedAnswersAreCheckedOnceAndTheUnreadableOnesAreHeldBackAsPersistedState() = runTest {
        store.applyChanges(listOf(SyncChange.SourceChanged(1, source(s1))), 1, 5)
        completeWith(s1)
        val broken = "33333333-3333-4333-8333-333333333333"
        val older = "44444444-4444-4444-8444-444444444444"
        storeRaw(broken, "{not json")
        storeRaw(older, "{\"text\":\"Paid.\"}")

        // The first ask looks at every stored answer once and holds back the two it cannot read.
        assertEquals(setOf(broken, older), cache.needingCheck().map { it.turnId }.toSet())
        assertEquals(3, cache.answersChecked)
        for (id in listOf(broken, older)) {
            val row = db.chat().turn(id)!!
            assertNull(row.answerJson)
            assertEquals("UNVERIFIED", row.localInvalid)
        }
        // The readable answer is untouched, and polling asks again and again without decoding anything.
        assertNotNull(cache.turnOnce(turnA)!!.answer)
        repeat(5) { assertEquals(setOf(broken, older), cache.needingCheck().map { it.turnId }.toSet()) }
        assertEquals(3, cache.answersChecked)

        // The held-back turns stay found until a read of the Worker's record replaces them.
        cache.applyServer("t1", listOf(server(id = broken, answer = answer(s1))), PageKind.Single, false, 90)
        assertEquals(listOf(older), cache.needingCheck().map { it.turnId })
        assertNotNull(cache.turnOnce(broken)!!.answer)
    }

    @Test
    fun theHistoryCursorSurvivesAReloadAndARefreshOfTheNewestPageDoesNotMoveIt() = runTest {
        cache.insertThread(thread("t1"))
        cache.applyServer("t1", listOf(server(id = turnB)), PageKind.Latest(turnB), true, 60)
        assertEquals(turnB, cache.threadOnce("t1")!!.olderCursor)
        assertTrue(cache.threadOnce("t1")!!.historyLoaded)
        cache.applyServer("t1", listOf(server(id = turnA)), PageKind.Earlier(turnB, turnA), true, 61)
        assertEquals(turnA, cache.thread("t1").first()!!.olderCursor)
        cache.applyServer("t1", listOf(server(id = turnB)), PageKind.Latest("22222222-2222-4222-8222-aaaaaaaaaaaa"), true, 62)
        assertEquals(turnA, cache.threadOnce("t1")!!.olderCursor)
        // A page for a cursor that is no longer current changes the turns it carries but never the cursor.
        cache.applyServer("t1", emptyList(), PageKind.Earlier(turnB, null), true, 63)
        assertEquals(turnA, cache.threadOnce("t1")!!.olderCursor)
        cache.applyServer("t1", emptyList(), PageKind.Earlier(turnA, null), true, 64)
        assertNull(cache.threadOnce("t1")!!.olderCursor)
        assertFalse(cache.threadOnce("t1")!!.olderAvailable)
    }

    @Test
    fun nothingIsStoredForAConversationThatSignOutCleared() = runTest {
        cache.insertThread(thread())
        cache.submit(turn(), 20)
        db.clearAllTables()
        assertFalse(cache.applyServer("t1", listOf(server()), PageKind.Latest(null), true, 60))
        assertNull(cache.turnOnce(turnA))
        assertTrue(cache.needingCheck().isEmpty())
        assertTrue(cache.threads().first().isEmpty())
    }

    @Test
    fun anAnswerCitingAnEmailNotOnThePhoneIsHeldBackUntilItArrives() = runTest {
        cache.insertThread(thread())
        cache.submit(turn(), 20)
        cache.markAttempted(turnA)
        cache.applyServer("t1", listOf(server(answer = answer(s1))), PageKind.Single, false, 60)
        val held = cache.turnOnce(turnA)!!
        assertNull(held.answer)
        assertEquals(LocalInvalid.UNVERIFIED, held.localInvalid)
        assertEquals(listOf(turnA), cache.needingCheck().map { it.turnId })

        store.applyChanges(listOf(SyncChange.SourceChanged(1, source(s1))), 1, 70)
        cache.applyServer("t1", listOf(server(answer = answer(s1))), PageKind.Single, false, 80)
        assertNotNull(cache.turnOnce(turnA)!!.answer)
        assertNull(cache.turnOnce(turnA)!!.localInvalid)
    }

    @Test
    fun historyInsertsUnknownTurnsOnlyIntoTheirOwnConversation() = runTest {
        cache.insertThread(thread("t1"))
        cache.insertThread(thread("t2"))
        cache.submit(turn(threadId = "t2"), 20)
        cache.applyServer("t1", listOf(server(id = turnA), server(id = turnB)), PageKind.Latest(turnB), true, 60)
        // turnA belongs to t2 and is left alone; turnB is new for t1.
        assertEquals("t2", cache.turnOnce(turnA)!!.threadLocalId)
        assertEquals(Dispatch.QUEUED, cache.turnOnce(turnA)!!.dispatch)
        assertEquals("t1", cache.turnOnce(turnB)!!.threadLocalId)
        assertEquals(turnB, cache.threadOnce("t1")!!.olderCursor)
    }

    @Test
    fun version3MigratesToVersion4AndAsksForAFullDownload() {
        migrations.createDatabase("migration-test-4", 3).use { old ->
            old.execSQL("INSERT INTO sync_state(id, throughSeq, needsBootstrap, lastSyncedAt) VALUES (0, 7, 0, 100)")
            old.execSQL(
                "INSERT INTO concerns(id, revision, title, status, importance, criticalBasis, readAt, acknowledgedAt, resolvedAt, snoozeUntil, waitingNote, awaitedParty, followUpAt, hasWaiting, dueAt, dueZone, dueSourceText, dueRevision, sourceIdsJson, updatedAt) " +
                    "VALUES ('c1', 1, 'Invoice', 'open', 'routine', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 0, NULL, NULL, NULL, NULL, '[]', 1)",
            )
        }
        migrations.runMigrationsAndValidate("migration-test-4", 4, true).use { migrated ->
            migrated.query("SELECT serviceId FROM concerns WHERE id = 'c1'").use { c ->
                c.moveToFirst()
                assertTrue(c.isNull(0))
            }
            migrated.query("SELECT needsBootstrap, throughSeq FROM sync_state").use { c ->
                c.moveToFirst()
                assertEquals(1, c.getInt(0))
                assertEquals(7, c.getInt(1))
            }
            migrated.query("SELECT COUNT(*) FROM chat_turns").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
            migrated.query("SELECT COUNT(*) FROM chat_threads").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
        }
    }
}
