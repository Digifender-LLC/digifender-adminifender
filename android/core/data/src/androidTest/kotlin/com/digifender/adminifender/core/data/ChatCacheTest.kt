package com.digifender.adminifender.core.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.digifender.adminifender.core.data.local.AppDatabase
import com.digifender.adminifender.core.data.local.RoomChatCache
import com.digifender.adminifender.core.data.local.RoomLocalStore
import com.digifender.adminifender.core.data.network.CitationDto
import com.digifender.adminifender.core.data.network.ClaimDto
import com.digifender.adminifender.core.data.network.SourceSummary
import com.digifender.adminifender.core.data.network.SyncChange
import com.digifender.adminifender.core.data.repository.ChatTurn
import com.digifender.adminifender.core.data.repository.TurnState
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val SOURCE = "0b4e6f8a-2c3d-4e5f-8a9b-1c2d3e4f5a6b"

/** Chat answers in Room on a device or emulator, and the version 2 to 3 migration (not run in CI yet). */
@RunWith(AndroidJUnit4::class)
class ChatCacheTest {
    @get:Rule
    val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    private lateinit var db: AppDatabase
    private lateinit var store: RoomLocalStore
    private lateinit var chat: RoomChatCache

    private fun source(quarantined: Boolean = false) = SourceSummary(SOURCE, 1, "ready", quarantined, "hybrid", 0, "s", emptyList())

    private fun answered() = ChatTurn(
        "t", "thread", "q", TurnState.COMPLETE, true, null, null, "Friday",
        listOf(ClaimDto("Due Friday", "evidence", listOf(CitationDto("c0", SOURCE, start = 0, end = 3)))), emptyList(), false, false, 0, 1,
    )

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        store = RoomLocalStore(db)
        chat = RoomChatCache(db)
    }

    @After
    fun close() = db.close()

    @Test
    fun deletionWithdrawsAnswersCitingTheSource() = runTest {
        store.applyChanges(listOf(SyncChange.SourceChanged(1, source())), 1, 0)
        chat.insert(answered())
        store.applyChanges(listOf(SyncChange.Removed(2, "source", SOURCE)), 2, 0)
        val turn = chat.turn("t")!!
        assertNull(turn.answer)
        assertTrue(turn.invalidated)
    }

    @Test
    fun quarantineWithholdsAnswers() = runTest {
        store.applyChanges(listOf(SyncChange.SourceChanged(1, source())), 1, 0)
        chat.insert(answered())
        store.applyChanges(listOf(SyncChange.SourceChanged(2, source(quarantined = true))), 2, 0)
        assertTrue(chat.turn("t")!!.withheld)
    }

    @Test
    fun version2MigratesWithItsRows() {
        migrations.createDatabase("migrate.db", 2).use { it.execSQL("INSERT INTO sync_state(id, throughSeq, needsBootstrap, lastSyncedAt) VALUES (0, 7, 0, 1)") }
        migrations.runMigrationsAndValidate("migrate.db", 3, true).use { db ->
            db.query("SELECT throughSeq FROM sync_state").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(7L, c.getLong(0))
            }
        }
    }
}
