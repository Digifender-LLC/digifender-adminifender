package com.digifender.adminifender.core.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Dao
interface ConcernDao {
    @Upsert
    suspend fun upsert(rows: List<ConcernEntity>)

    @Query("DELETE FROM concerns WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM concerns")
    suspend fun clear()

    @Query("SELECT revision FROM concerns WHERE id = :id")
    suspend fun revision(id: String): Int?

    @Query("SELECT * FROM concerns WHERE id = :id")
    suspend fun get(id: String): ConcernEntity?

    @Query("SELECT * FROM concerns")
    suspend fun all(): List<ConcernEntity>

    @Query("SELECT * FROM concerns WHERE status <> 'resolved' ORDER BY updatedAt DESC, id")
    fun observeUnresolved(): Flow<List<ConcernEntity>>

    @Query("SELECT * FROM concerns WHERE id = :id")
    fun observe(id: String): Flow<ConcernEntity?>

    @Query("SELECT * FROM concerns")
    fun observeAll(): Flow<List<ConcernEntity>>
}

@Dao
interface SourceDao {
    @Upsert
    suspend fun upsert(rows: List<SourceEntity>)

    @Query("DELETE FROM sources WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM sources")
    suspend fun clear()

    @Query("SELECT * FROM sources WHERE quarantined = :quarantined ORDER BY receivedAt DESC, id LIMIT :limit")
    fun observe(quarantined: Boolean, limit: Int): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources WHERE id = :id")
    fun observe(id: String): Flow<SourceEntity?>

    @Query("SELECT * FROM sources WHERE id IN (:ids)")
    fun observeIds(ids: List<String>): Flow<List<SourceEntity>>

    @Query("SELECT revision FROM sources WHERE id = :id")
    suspend fun revision(id: String): Int?

    @Query("SELECT COUNT(*) FROM sources WHERE quarantined = :quarantined")
    fun count(quarantined: Boolean): Flow<Int>

    /** Cached-only search; built by `CachedSearch` with bound LIKE parameters only. */
    @RawQuery(observedEntities = [SourceEntity::class, SourceTextEntity::class])
    suspend fun search(query: SupportSQLiteQuery): List<CachedHit>
}

/** One cached search row: source metadata plus the cached body when the owner has opened it before. */
data class CachedHit(
    val id: String,
    val revision: Int,
    val state: String,
    val quarantined: Boolean,
    val privacy: String,
    val receivedAt: Long,
    val subject: String?,
    val fromJson: String,
    val text: String?,
)

@Dao
interface SourceTextDao {
    @Upsert
    suspend fun put(row: SourceTextEntity)

    @Query("SELECT * FROM source_texts WHERE sourceId = :id")
    fun observe(id: String): Flow<SourceTextEntity?>

    /** Drops text whose source is gone or has moved to another revision. */
    @Query("DELETE FROM source_texts WHERE NOT EXISTS (SELECT 1 FROM sources s WHERE s.id = source_texts.sourceId AND s.revision = source_texts.revision)")
    suspend fun pruneStale()
}

@Dao
interface MutationDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: PendingMutationEntity)

    @Query("SELECT * FROM pending_mutations ORDER BY createdAt, rowid")
    suspend fun all(): List<PendingMutationEntity>

    @Query("SELECT * FROM pending_mutations ORDER BY createdAt, rowid")
    fun observeAll(): Flow<List<PendingMutationEntity>>

    @Query("SELECT * FROM pending_mutations WHERE idempotencyKey = :key")
    suspend fun get(key: String): PendingMutationEntity?

    @Query("SELECT COUNT(*) FROM pending_mutations WHERE concernId = :concernId AND state IN ('QUEUED', 'HELD')")
    suspend fun openCount(concernId: String): Int

    @Query("DELETE FROM pending_mutations WHERE idempotencyKey = :key")
    suspend fun delete(key: String)

    @Query("UPDATE pending_mutations SET state = :state, attempts = :attempts, nextAttemptAt = :nextAttemptAt, errorCode = :errorCode, currentRevision = :currentRevision WHERE idempotencyKey = :key")
    suspend fun update(key: String, state: String, attempts: Int, nextAttemptAt: Long, errorCode: String?, currentRevision: Int?)

    @Query("UPDATE pending_mutations SET state = 'CONFLICT', errorCode = 'source_deleted', currentRevision = NULL WHERE concernId = :concernId AND state IN ('QUEUED', 'HELD')")
    suspend fun holdAfterDeletion(concernId: String)
}

@Dao
interface DeletionFenceDao {
    @Upsert
    suspend fun suppress(rows: List<SuppressedSourceEntity>)

    @Upsert
    suspend fun fence(row: ConcernDeletionFenceEntity)

    @Query("SELECT id FROM suppressed_sources")
    suspend fun suppressedIds(): List<String>

    @Query("SELECT revision FROM concern_deletion_fences WHERE id = :id")
    suspend fun revision(id: String): Int?
}

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE id = 0")
    suspend fun get(): SyncStateEntity?

    @Query("SELECT * FROM sync_state WHERE id = 0")
    fun observe(): Flow<SyncStateEntity?>

    @Upsert
    suspend fun put(row: SyncStateEntity)
}
