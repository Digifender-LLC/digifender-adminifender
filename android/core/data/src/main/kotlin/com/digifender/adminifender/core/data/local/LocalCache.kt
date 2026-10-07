package com.digifender.adminifender.core.data.local

import android.content.Context
import androidx.room.withTransaction
import com.digifender.adminifender.core.data.repository.ArchiveCache
import com.digifender.adminifender.core.data.repository.CacheSource
import com.digifender.adminifender.core.data.sync.LocalStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The app's private cache: the Room database with its read flows and transactional store. */
class LocalCache internal constructor(private val db: AppDatabase) {
    constructor(context: Context) : this(AppDatabase.open(context.applicationContext))
    val store: LocalStore = RoomLocalStore(db)
    val source: CacheSource = RoomCacheSource(db)
    val archive: ArchiveCache = RoomArchiveCache(db)

    /**
     * A confirmed deletion hides source text and every affected concern title immediately. Persisted fences also
     * reject pages fetched before confirmation, across process death/rebootstrap. Pending edits are retained as
     * explicit conflicts and the sync cursor is unchanged; newer sanitized concern projections can return.
     */
    suspend fun invalidateSources(ids: List<String>, concernRevisions: Map<String, Int> = emptyMap()) = db.withTransaction {
        val suppressed = ids.toSet()
        db.deletionFences().suppress(ids.map(::SuppressedSourceEntity))
        val affected = concernRevisions.toMutableMap()
        for (row in db.concerns().all()) {
            if (row.toDomain().sourceIds.any { it in suppressed }) affected[row.id] = maxOf(affected[row.id] ?: 0, row.revision)
        }
        for ((id, revision) in affected) {
            val highest = maxOf(revision, db.deletionFences().revision(id) ?: 0)
            db.deletionFences().fence(ConcernDeletionFenceEntity(id, highest))
            val current = db.concerns().get(id)
            // Sync may already have applied the deletion's newer sanitized projection before HTTP readback.
            // Keep it: its change cursor may have advanced, so another ordinary sync need not send it again.
            if (current == null || current.revision <= highest || current.toDomain().sourceIds.any { it in suppressed }) {
                db.concerns().delete(id)
            }
            db.mutations().holdAfterDeletion(id)
        }
        ids.forEach { db.sources().delete(it) }
        db.sourceTexts().pruneStale()
    }

    /** Removes every cached projection and queued edit, for sign-out (lld.md#sync). */
    suspend fun clear() = withContext(Dispatchers.IO) { db.clearAllTables() }
}
