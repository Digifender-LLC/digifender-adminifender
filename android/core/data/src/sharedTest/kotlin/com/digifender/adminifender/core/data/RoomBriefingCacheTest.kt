package com.digifender.adminifender.core.data

import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.digifender.adminifender.core.data.local.AppDatabase
import com.digifender.adminifender.core.data.local.RoomBriefingCache
import com.digifender.adminifender.core.data.repository.Briefing
import com.digifender.adminifender.core.data.repository.BriefingItem
import com.digifender.adminifender.core.data.repository.ReviewPhase
import com.digifender.adminifender.core.data.repository.StoredReview
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

/**
 * Briefing cache on Room. Written for a device or emulator and NOT yet compiled or run (no Android SDK was available
 * when slice 14g was written); the first Android build must compile it before it counts as evidence.
 */
@RunWith(AndroidJUnit4::class)
class RoomBriefingCacheTest {
    private lateinit var db: AppDatabase
    private lateinit var cache: RoomBriefingCache

    @get:Rule
    val migrations = migrationHelper()

    private fun briefing(id: String, seq: Long = 5) =
        Briefing(id, seq, 100, null, 200, listOf(BriefingItem("c1", 2)), emptyList(), listOf(BriefingItem("c2", 3)), listOf(BriefingItem("c1", 2)), listOf("s1"))

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
        cache = RoomBriefingCache(db)
    }

    @After
    fun close() = db.close()

    @Test
    fun storesOnlyReferencesAndReadsThemBackInOrder() = runTest {
        assertNull(cache.current().first())
        cache.replace(briefing("b1"))
        val record = cache.current().first()!!
        assertEquals(briefing("b1"), record.briefing)
        assertEquals(StoredReview.NONE, record.review)
    }

    @Test
    fun theSameSnapshotKeepsItsPendingReviewAndANewOneStartsClean() = runTest {
        cache.replace(briefing("b1"))
        assertTrue(cache.setReview("b1", StoredReview(ReviewPhase.UNCONFIRMED, "key-1")))
        cache.replace(briefing("b1", seq = 6))
        assertEquals(StoredReview(ReviewPhase.UNCONFIRMED, "key-1"), cache.current().first()!!.review)
        cache.replace(briefing("b2", seq = 9))
        assertEquals(StoredReview.NONE, cache.current().first()!!.review)
        // Only one row is ever held, and a review for the replaced briefing no longer applies.
        assertFalse(cache.setReview("b1", StoredReview(ReviewPhase.DONE, null, 1)))
        assertEquals("b2", cache.current().first()!!.briefing.id)
    }

    @Test
    fun clearingAllTablesForSignOutRemovesTheBriefing() = runTest {
        cache.replace(briefing("b1"))
        cache.setReview("b1", StoredReview(ReviewPhase.UNCONFIRMED, "key-1"))
        // Blocking, like production's sign-out: off the main thread, which Robolectric's test thread is.
        withContext(Dispatchers.IO) { db.clearAllTables() }
        assertNull(cache.current().first())
        assertFalse(cache.setReview("b1", StoredReview.NONE))
    }

    @Test
    fun version2MigratesToVersion3WithTheCacheIntact() {
        migrations.createDatabase("migration-test-3", 2).use { old ->
            old.execSQL("INSERT INTO sources(id, revision, state, quarantined, privacy, receivedAt, subject, fromJson) VALUES ('a', 1, 'ready', 0, 'hybrid', 1, 's', '[]')")
        }
        migrations.runMigrationsAndValidate("migration-test-3", 3, true).use { db ->
            db.query("SELECT COUNT(*) FROM sources").use { c ->
                c.moveToFirst()
                assertEquals(1, c.getInt(0))
            }
            db.query("SELECT COUNT(*) FROM briefing").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
        }
    }
}
