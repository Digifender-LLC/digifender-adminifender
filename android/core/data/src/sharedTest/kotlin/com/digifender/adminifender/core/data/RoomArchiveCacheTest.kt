package com.digifender.adminifender.core.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.digifender.adminifender.core.data.local.AppDatabase
import com.digifender.adminifender.core.data.local.RoomArchiveCache
import com.digifender.adminifender.core.data.local.RoomLocalStore
import com.digifender.adminifender.core.data.network.ArchiveView
import com.digifender.adminifender.core.data.network.SourceSummary
import com.digifender.adminifender.core.data.network.SyncChange
import com.digifender.adminifender.core.data.repository.SourceText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Cached text and cached-only search on Room (runs on the JVM under Robolectric and on a device or emulator). */
@RunWith(AndroidJUnit4::class)
class RoomArchiveCacheTest {
    private lateinit var db: AppDatabase
    private lateinit var store: RoomLocalStore
    private lateinit var cache: RoomArchiveCache

    @get:Rule
    val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    private fun source(id: String, revision: Int = 1, subject: String = "Water bill", quarantined: Boolean = false, from: String = "billing@water.example", at: Long = 100) =
        SourceSummary(id, revision, "ready", quarantined, "hybrid", at, subject, listOf(from))

    private fun text(revision: Int = 1, body: String = "Please pay 50% by Friday") = SourceText(revision, body, true, emptyList())

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        store = RoomLocalStore(db)
        cache = RoomArchiveCache(db)
    }

    @After
    fun close() = db.close()

    @Test
    fun textIsKeptOnlyForTheCachedRevisionAndDroppedWhenTheSourceChangesOrGoes() = runTest {
        store.applyChanges(listOf(SyncChange.SourceChanged(1, source("a")), SyncChange.SourceChanged(2, source("b"))), 2, 10)
        assertFalse(cache.putText("missing", text(), 10))
        assertFalse(cache.putText("a", text(revision = 2), 10))
        assertTrue(cache.putText("a", text(), 10))
        assertTrue(cache.putText("b", text(), 10))

        store.applyChanges(listOf(SyncChange.SourceChanged(3, source("a", revision = 2)), SyncChange.Removed(4, "source", "b")), 4, 20)
        assertNull(cache.text("a").first())
        assertNull(cache.text("b").first())
    }

    @Test
    fun aSnapshotWithoutTheSourceDropsItsText() = runTest {
        store.applyChanges(listOf(SyncChange.SourceChanged(1, source("a"))), 1, 10)
        assertTrue(cache.putText("a", text(), 10))
        store.replaceWithSnapshot(emptyList(), 5, 20)
        assertNull(cache.text("a").first())
    }

    @Test
    fun cachedSearchMatchesEveryTermLiterallyInTheSelectedView() = runTest {
        store.applyChanges(
            listOf(
                SyncChange.SourceChanged(1, source("a", at = 300)),
                SyncChange.SourceChanged(2, source("b", subject = "Holiday", at = 200)),
                SyncChange.SourceChanged(3, source("q", subject = "Water bill", quarantined = true)),
            ),
            3,
            10,
        )
        cache.putText("b", text(body = "pay the water company"), 10)
        // Every term must match somewhere (subject, sender or cached text): only b has both "water" and "holiday",
        // and nothing has "invoice". ("bill" would match b too, through its billing@ sender.)
        assertEquals(listOf("b"), cache.search(listOf("water", "holiday"), ArchiveView.ARCHIVE, null, null, null, 50).map { it.source.id })
        assertEquals(emptyList<String>(), cache.search(listOf("water", "invoice"), ArchiveView.ARCHIVE, null, null, null, 50).map { it.source.id })
        assertEquals(listOf("a", "b"), cache.search(listOf("water"), ArchiveView.ARCHIVE, null, null, null, 50).map { it.source.id })
        assertEquals(listOf("q"), cache.search(listOf("water"), ArchiveView.QUARANTINE, null, null, null, 50).map { it.source.id })
        // LIKE wildcards in owner text are literal.
        assertEquals(emptyList<String>(), cache.search(listOf("p_y"), ArchiveView.ARCHIVE, null, null, null, 50).map { it.source.id })
        assertEquals(emptyList<String>(), cache.search(listOf("%"), ArchiveView.ARCHIVE, null, null, null, 50).map { it.source.id })
        assertEquals(listOf("a"), cache.search(listOf("water"), ArchiveView.ARCHIVE, null, 250, null, 50).map { it.source.id })
        assertEquals(listOf("a", "b"), cache.search(listOf("water"), ArchiveView.ARCHIVE, "billing@water.example", null, null, 50).map { it.source.id })
        assertEquals(emptyList<String>(), cache.search(listOf("water"), ArchiveView.ARCHIVE, "water.example", null, null, 50).map { it.source.id })
    }

    @Test
    fun version1MigratesToVersion2WithTheCacheIntact() {
        migrations.createDatabase("migration-test", 1).use { old ->
            old.execSQL("INSERT INTO sources(id, revision, state, quarantined, privacy, receivedAt, subject, fromJson) VALUES ('a', 1, 'ready', 0, 'hybrid', 1, 's', '[]')")
        }
        migrations.runMigrationsAndValidate("migration-test", 2, true).use { db ->
            db.query("SELECT COUNT(*) FROM sources").use { c ->
                c.moveToFirst()
                assertEquals(1, c.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM source_texts").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
        }
    }
}
