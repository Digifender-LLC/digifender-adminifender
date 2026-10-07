package com.digifender.adminifender.core.data.local

import androidx.room.withTransaction
import com.digifender.adminifender.core.data.network.SourceSummary
import com.digifender.adminifender.core.data.network.SyncChange
import com.digifender.adminifender.core.data.network.wireJson
import com.digifender.adminifender.core.data.sync.ActionCodec
import com.digifender.adminifender.core.data.sync.LocalStore
import com.digifender.adminifender.core.data.sync.MutationState
import com.digifender.adminifender.core.data.sync.QueuedMutation
import com.digifender.adminifender.core.data.sync.SyncState
import com.digifender.adminifender.core.model.Concern
import com.digifender.adminifender.core.model.ConcernAction
import com.digifender.adminifender.core.model.Due
import com.digifender.adminifender.core.model.Importance
import com.digifender.adminifender.core.model.OutboxDecision
import com.digifender.adminifender.core.model.PendingMutation
import com.digifender.adminifender.core.model.Status
import com.digifender.adminifender.core.model.Waiting
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

private val stringList = ListSerializer(String.serializer())

fun Concern.toEntity() = ConcernEntity(
    id = id, revision = revision, title = title, status = status.wire, importance = importance.wire, criticalBasis = criticalBasis,
    readAt = readAtMs, acknowledgedAt = acknowledgedAtMs, resolvedAt = resolvedAtMs, snoozeUntil = snoozeUntilMs,
    waitingNote = waiting?.note, awaitedParty = waiting?.awaitedParty, followUpAt = waiting?.followUpAtMs, hasWaiting = waiting != null,
    dueAt = due?.atMs, dueZone = due?.zone, dueSourceText = due?.sourceText, dueRevision = due?.revision,
    sourceIdsJson = wireJson.encodeToString(stringList, sourceIds), updatedAt = updatedAtMs,
)

fun ConcernEntity.toDomain() = Concern(
    id = id, revision = revision, title = title, status = Status.fromWire(status) ?: Status.OPEN, importance = Importance.fromWire(importance) ?: Importance.ROUTINE,
    criticalBasis = criticalBasis, readAtMs = readAt, acknowledgedAtMs = acknowledgedAt, resolvedAtMs = resolvedAt, snoozeUntilMs = snoozeUntil,
    waiting = if (hasWaiting) Waiting(waitingNote, awaitedParty, followUpAt) else null,
    due = dueRevision?.let { Due(dueAt, dueZone, dueSourceText, it) },
    sourceIds = wireJson.decodeFromString(stringList, sourceIdsJson), updatedAtMs = updatedAt,
)

fun SourceSummary.toEntity() = SourceEntity(id, revision, state, quarantined, privacy, receivedAtMs, subject, wireJson.encodeToString(stringList, from))

fun PendingMutationEntity.toQueued() = QueuedMutation(
    mutation = PendingMutation(idempotencyKey, concernId, expectedRevision, ActionCodec.decode(kind, argsJson), createdAt, attempts),
    state = MutationState.valueOf(state),
    nextAttemptAtMs = nextAttemptAt,
    errorCode = errorCode,
    currentRevision = currentRevision,
)

/** [LocalStore] on Room; each method is a single transaction. */
class RoomLocalStore(private val db: AppDatabase) : LocalStore {
    override suspend fun syncState(): SyncState {
        val row = db.syncState().get() ?: return SyncState(throughSeq = 0, needsBootstrap = true, lastSyncedAtMs = null)
        return SyncState(row.throughSeq, row.needsBootstrap, row.lastSyncedAt)
    }

    private suspend fun write(changes: List<SyncChange>) {
        val suppressed = db.deletionFences().suppressedIds().toMutableSet()
        for (change in changes) {
            when (change) {
                is SyncChange.ConcernChanged -> {
                    val concern = change.concern
                    if (concern.sourceIds.none { it in suppressed } && concern.revision > (db.deletionFences().revision(concern.id) ?: 0)) {
                        db.concerns().upsert(listOf(concern.toEntity()))
                    }
                }
                is SyncChange.SourceChanged -> if (change.source.id !in suppressed) db.sources().upsert(listOf(change.source.toEntity()))
                is SyncChange.Removed -> when (change.entityType) {
                    "concern" -> {
                        db.deletionFences().fence(ConcernDeletionFenceEntity(change.entityId, Int.MAX_VALUE))
                        db.concerns().delete(change.entityId)
                        db.mutations().holdAfterDeletion(change.entityId)
                    }
                    "source" -> {
                        LocalCache(db).invalidateSources(listOf(change.entityId))
                        suppressed += change.entityId
                    }
                }
                is SyncChange.Ignored -> Unit
            }
        }
        // Cached text survives only for an unchanged source; deletion and revision changes drop it here.
        db.sourceTexts().pruneStale()
    }

    override suspend fun applyChanges(changes: List<SyncChange>, throughSeq: Long, nowMs: Long) = db.withTransaction {
        write(changes)
        db.syncState().put(SyncStateEntity(throughSeq = throughSeq, needsBootstrap = false, lastSyncedAt = nowMs))
    }

    override suspend fun replaceWithSnapshot(changes: List<SyncChange>, snapshotSeq: Long, nowMs: Long) = db.withTransaction {
        db.concerns().clear()
        db.sources().clear()
        write(changes)
        db.syncState().put(SyncStateEntity(throughSeq = snapshotSeq, needsBootstrap = false, lastSyncedAt = nowMs))
    }

    override suspend fun markNeedsBootstrap() = db.withTransaction {
        val current = db.syncState().get()
        db.syncState().put(SyncStateEntity(throughSeq = current?.throughSeq ?: 0, needsBootstrap = true, lastSyncedAt = current?.lastSyncedAt))
    }

    override suspend fun mutations(): List<QueuedMutation> = db.mutations().all().map { it.toQueued() }

    override suspend fun enqueue(key: String, concernId: String, action: ConcernAction, nowMs: Long): PendingMutation? = enqueueGuarded(key, concernId, null, action, nowMs)

    override suspend fun enqueueIfRevision(key: String, concernId: String, expectedRevision: Int, action: ConcernAction, nowMs: Long): PendingMutation? = enqueueGuarded(key, concernId, expectedRevision, action, nowMs)

    private suspend fun enqueueGuarded(key: String, concernId: String, expectedRevision: Int?, action: ConcernAction, nowMs: Long): PendingMutation? = db.withTransaction {
        val revision = db.concerns().revision(concernId) ?: return@withTransaction null
        if (expectedRevision != null && revision != expectedRevision) return@withTransaction null
        val expected = revision + db.mutations().openCount(concernId)
        val (kind, args) = ActionCodec.encode(action)
        db.mutations().insert(PendingMutationEntity(key, concernId, expected, kind, args, nowMs, 0, MutationState.QUEUED.name, nowMs, null, null))
        PendingMutation(key, concernId, expected, action, nowMs)
    }

    override suspend fun record(key: String, decision: OutboxDecision, nowMs: Long) = db.withTransaction {
        val row = db.mutations().get(key) ?: return@withTransaction
        // An old HTTP failure must not requeue work after deletion made its target unsafe to display/reapply.
        if (row.errorCode == "source_deleted" && decision != OutboxDecision.Remove) return@withTransaction
        when (decision) {
            OutboxDecision.Remove -> db.mutations().delete(key)
            is OutboxDecision.Retry -> db.mutations().update(key, MutationState.QUEUED.name, decision.next.attempts, nowMs + decision.delayMs, null, null)
            is OutboxDecision.Conflict -> db.mutations().update(key, MutationState.CONFLICT.name, row.attempts, row.nextAttemptAt, "stale_revision", decision.currentRevision)
            is OutboxDecision.Failed -> db.mutations().update(key, MutationState.FAILED.name, row.attempts, row.nextAttemptAt, decision.code, null)
            OutboxDecision.HoldForSignIn -> db.mutations().update(key, MutationState.HELD.name, row.attempts, nowMs, null, null)
        }
    }

    override suspend fun discard(key: String) = db.mutations().delete(key)

    override suspend fun reapply(key: String, newKey: String, nowMs: Long): PendingMutation? = db.withTransaction {
        val row = db.mutations().get(key) ?: return@withTransaction null
        val revision = db.concerns().revision(row.concernId) ?: return@withTransaction null
        db.mutations().delete(key)
        val expected = revision + db.mutations().openCount(row.concernId)
        db.mutations().insert(row.copy(idempotencyKey = newKey, expectedRevision = expected, createdAt = nowMs, attempts = 0, state = MutationState.QUEUED.name, nextAttemptAt = nowMs, errorCode = null, currentRevision = null))
        PendingMutation(newKey, row.concernId, expected, ActionCodec.decode(row.kind, row.argsJson), nowMs)
    }
}
