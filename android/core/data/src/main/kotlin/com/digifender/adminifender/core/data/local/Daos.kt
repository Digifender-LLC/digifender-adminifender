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

    @Query("DELETE FROM sources WHERE id IN (:ids)")
    suspend fun deleteMany(ids: List<String>)

    @Query("SELECT * FROM sources WHERE quarantined = :quarantined ORDER BY receivedAt DESC, id LIMIT :limit")
    fun observe(quarantined: Boolean, limit: Int): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources WHERE id = :id")
    fun observe(id: String): Flow<SourceEntity?>

    @Query("SELECT * FROM sources WHERE id IN (:ids)")
    fun observeIds(ids: List<String>): Flow<List<SourceEntity>>

    @Query("SELECT revision FROM sources WHERE id = :id")
    suspend fun revision(id: String): Int?

    @Query("SELECT * FROM sources WHERE id = :id")
    suspend fun get(id: String): SourceEntity?

    @Query("SELECT * FROM sources WHERE id IN (:ids)")
    suspend fun getMany(ids: List<String>): List<SourceEntity>

    @Query("SELECT * FROM sources")
    suspend fun all(): List<SourceEntity>

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

@Dao
interface BriefingDao {
    @Query("SELECT * FROM briefing LIMIT 1")
    fun observe(): Flow<BriefingEntity?>

    @Query("SELECT * FROM briefing LIMIT 1")
    suspend fun get(): BriefingEntity?

    @Query("DELETE FROM briefing")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: BriefingEntity)

    /** Returns 0 when the stored briefing is no longer [id]. */
    @Query("UPDATE briefing SET reviewPhase = :phase, reviewKey = :key, reviewedAt = :reviewedAt WHERE briefingId = :id")
    suspend fun setReview(id: String, phase: String, key: String?, reviewedAt: Long?): Int
}

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_threads ORDER BY updatedAt DESC, localId")
    fun observeThreads(): Flow<List<ChatThreadEntity>>

    @Query("SELECT * FROM chat_threads WHERE localId = :id")
    fun observeThread(id: String): Flow<ChatThreadEntity?>

    @Query("SELECT * FROM chat_threads WHERE localId = :id")
    suspend fun thread(id: String): ChatThreadEntity?

    /** A thread the Worker refused is never reused for new questions. */
    @Query("SELECT * FROM chat_threads WHERE scopeKind = :kind AND ((:id IS NULL AND scopeId IS NULL) OR scopeId = :id) AND createState <> 'REFUSED' ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latest(kind: String, id: String?): ChatThreadEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertThread(row: ChatThreadEntity)

    @Query("UPDATE chat_threads SET draft = :draft WHERE localId = :id")
    suspend fun setDraft(id: String, draft: String): Int

    @Query("UPDATE chat_threads SET createState = :state, serverId = :serverId, createError = :error WHERE localId = :id")
    suspend fun setCreate(id: String, state: String, serverId: String?, error: String?): Int

    @Query("UPDATE chat_threads SET olderCursor = :cursor, historyLoaded = :loaded WHERE localId = :id")
    suspend fun setHistory(id: String, cursor: String?, loaded: Boolean): Int

    @Query("UPDATE chat_threads SET updatedAt = :now WHERE localId = :id")
    suspend fun touch(id: String, now: Long): Int

    @Query("SELECT * FROM chat_turns WHERE threadLocalId = :id ORDER BY createdAt, rowid")
    fun observeTurns(id: String): Flow<List<ChatTurnEntity>>

    @Query("SELECT * FROM chat_turns WHERE threadLocalId = :id ORDER BY createdAt, rowid")
    suspend fun turns(id: String): List<ChatTurnEntity>

    @Query("SELECT * FROM chat_turns WHERE turnId = :id")
    suspend fun turn(id: String): ChatTurnEntity?

    @Query("SELECT * FROM chat_turns WHERE turnId IN (:ids)")
    suspend fun turnsById(ids: List<String>): List<ChatTurnEntity>

    @Query("SELECT * FROM chat_turns WHERE answerJson IS NOT NULL")
    suspend fun answered(): List<ChatTurnEntity>

    @Query("SELECT COUNT(*) FROM chat_turns WHERE dispatch IN ('QUEUED', 'ATTEMPTED')")
    fun observeUnconfirmed(): Flow<Int>

    @Query("SELECT COUNT(*) FROM chat_turns WHERE dispatch = 'ACCEPTED' AND (state IS NULL OR state IN ('PENDING', 'RUNNING'))")
    fun observeAtWorker(): Flow<Int>

    @Query("SELECT COUNT(*) FROM chat_threads WHERE TRIM(draft) <> ''")
    fun observeDrafts(): Flow<Int>

    @Query(
        "SELECT * FROM chat_turns WHERE dispatch IN ('QUEUED', 'ATTEMPTED') OR (dispatch = 'ACCEPTED' AND (state IS NULL OR state IN ('PENDING', 'RUNNING'))) " +
            "OR localInvalid = 'UNVERIFIED' ORDER BY createdAt, rowid",
    )
    suspend fun needingCheck(): List<ChatTurnEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTurn(row: ChatTurnEntity)

    @Upsert
    suspend fun upsertTurns(rows: List<ChatTurnEntity>)

    @Query("DELETE FROM chat_turns WHERE turnId = :id")
    suspend fun deleteTurn(id: String): Int

    @Query("UPDATE chat_turns SET dispatch = :next, dispatchError = :error WHERE turnId = :id AND dispatch IN (:allowed)")
    suspend fun setDispatch(id: String, next: String, error: String?, allowed: List<String>): Int

    @Query("UPDATE chat_turns SET cancelNote = :note WHERE turnId = :id")
    suspend fun setCancelNote(id: String, note: String): Int

    /** Removes stored answer text that cannot be read and asks for the turn to be read again; a removed answer stays removed. */
    @Query("UPDATE chat_turns SET answerJson = NULL, localInvalid = 'UNVERIFIED' WHERE turnId IN (:ids) AND localInvalid IS NULL")
    suspend fun holdBack(ids: List<String>): Int

    /** Removes the answer for good: the row keeps the question and the reason, never the model's text or the excerpts. */
    @Query("UPDATE chat_turns SET answerJson = NULL, localInvalid = 'SOURCE_CHANGED' WHERE turnId IN (:ids)")
    suspend fun invalidate(ids: List<String>): Int
}

@Dao
interface LocalFileDao {
    @Query("SELECT * FROM local_files ORDER BY requestedAt DESC, attachmentId")
    fun observeAll(): Flow<List<LocalFileEntity>>

    @Query("SELECT * FROM local_files WHERE sourceId = :sourceId")
    fun observeSource(sourceId: String): Flow<List<LocalFileEntity>>

    @Query("SELECT * FROM local_files WHERE attachmentId = :id")
    suspend fun get(id: String): LocalFileEntity?

    @Query("SELECT * FROM local_files")
    suspend fun all(): List<LocalFileEntity>

    @Upsert
    suspend fun put(row: LocalFileEntity)

    @Query("DELETE FROM local_files WHERE attachmentId = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM local_files")
    suspend fun clear()

    @Query("SELECT * FROM local_files WHERE sourceId IN (:ids)")
    suspend fun forSources(ids: List<String>): List<LocalFileEntity>

    @Query("DELETE FROM local_files WHERE sourceId IN (:ids)")
    suspend fun deleteForSources(ids: List<String>)

    @Query("SELECT blobName FROM local_files WHERE blobName IS NOT NULL")
    suspend fun blobNames(): List<String>
}

@Dao
interface TombstoneDao {
    @Upsert
    suspend fun put(rows: List<SourceTombstoneEntity>)

    @Query("SELECT sourceId FROM source_tombstones")
    suspend fun ids(): List<String>

    @Query("DELETE FROM source_tombstones WHERE sourceId IN (:ids)")
    suspend fun delete(ids: List<String>)

    @Query("DELETE FROM source_tombstones WHERE recordedAt < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM source_tombstones")
    suspend fun clear()
}

@Dao
interface DeletionReviewDao {
    @Query("SELECT * FROM deletion_reviews ORDER BY createdAt DESC, localId")
    fun observeAll(): Flow<List<DeletionReviewEntity>>

    @Query("SELECT * FROM deletion_reviews WHERE localId = :id")
    fun observe(id: String): Flow<DeletionReviewEntity?>

    @Query("SELECT * FROM deletion_reviews WHERE localId = :id")
    suspend fun get(id: String): DeletionReviewEntity?

    @Query("SELECT * FROM deletion_reviews")
    suspend fun all(): List<DeletionReviewEntity>

    @Upsert
    suspend fun put(row: DeletionReviewEntity)

    @Query("DELETE FROM deletion_reviews WHERE localId = :id")
    suspend fun delete(id: String)
}

@Dao
interface ExportIntentDao {
    @Query("SELECT * FROM export_intents ORDER BY createdAt DESC, localId")
    fun observeAll(): Flow<List<ExportIntentEntity>>

    @Query("SELECT * FROM export_intents WHERE localId = :id")
    fun observe(id: String): Flow<ExportIntentEntity?>

    @Query("SELECT * FROM export_intents WHERE localId = :id")
    suspend fun get(id: String): ExportIntentEntity?

    @Query("SELECT * FROM export_intents")
    suspend fun all(): List<ExportIntentEntity>

    @Upsert
    suspend fun put(row: ExportIntentEntity)

    @Query("DELETE FROM export_intents WHERE localId = :id")
    suspend fun delete(id: String)
}
