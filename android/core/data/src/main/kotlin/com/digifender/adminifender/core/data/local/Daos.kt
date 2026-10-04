package com.digifender.adminifender.core.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
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
