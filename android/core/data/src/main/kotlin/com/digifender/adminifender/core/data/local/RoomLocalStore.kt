package com.digifender.adminifender.core.data.local

import androidx.room.withTransaction
import com.digifender.adminifender.core.data.network.SourceSummary
import com.digifender.adminifender.core.data.network.SyncChange
import com.digifender.adminifender.core.data.network.wireJson
import com.digifender.adminifender.core.data.repository.SourceFact
import com.digifender.adminifender.core.data.repository.restricts
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
    sourceIdsJson = wireJson.encodeToString(stringList, sourceIds), updatedAt = updatedAtMs, serviceId = serviceId,
)

fun ConcernEntity.toDomain() = Concern(
    id = id, revision = revision, title = title, status = Status.fromWire(status) ?: Status.OPEN, importance = Importance.fromWire(importance) ?: Importance.ROUTINE,
    criticalBasis = criticalBasis, readAtMs = readAt, acknowledgedAtMs = acknowledgedAt, resolvedAtMs = resolvedAt, snoozeUntilMs = snoozeUntil,
    waiting = if (hasWaiting) Waiting(waitingNote, awaitedParty, followUpAt) else null,
    due = dueRevision?.let { Due(dueAt, dueZone, dueSourceText, it) },
    sourceIds = wireJson.decodeFromString(stringList, sourceIdsJson), updatedAtMs = updatedAt, serviceId = serviceId,
)

fun SourceSummary.toEntity() = SourceEntity(id, revision, state, quarantined, privacy, receivedAtMs, subject, wireJson.encodeToString(stringList, from))

fun PendingMutationEntity.toQueued() = QueuedMutation(
    mutation = PendingMutation(idempotencyKey, concernId, expectedRevision, ActionCodec.decode(kind, argsJson), createdAt, attempts),
    state = MutationState.valueOf(state),
    nextAttemptAtMs = nextAttemptAt,
    errorCode = errorCode,
    currentRevision = currentRevision,
)

private fun SourceSummary.toFact() = SourceFact(id, quarantined, privacy, state)

/** [LocalStore] on Room; each method is a single transaction. */
class RoomLocalStore(private val db: AppDatabase) : LocalStore {
    private val chat = RoomChatCache(db)

    override suspend fun syncState(): SyncState {
        val row = db.syncState().get() ?: return SyncState(throughSeq = 0, needsBootstrap = true, lastSyncedAtMs = null)
        return SyncState(row.throughSeq, row.needsBootstrap, row.lastSyncedAt)
    }

    /**
     * Applies [changes] and returns the emails whose change may have made a stored chat answer unsafe (see
     * [restricts]). [held] is the set of emails the phone held before a full snapshot replaced them; without it each
     * email's previous state is read from the table.
     */
    private suspend fun write(changes: List<SyncChange>, held: Map<String, SourceFact>? = null): MutableSet<String> {
        val restricted = mutableSetOf<String>()
        for (change in changes) {
            when (change) {
                is SyncChange.ConcernChanged -> db.concerns().upsert(listOf(change.concern.toEntity()))
                is SyncChange.SourceChanged -> {
                    val old = if (held != null) held[change.source.id] else db.sources().get(change.source.id)?.toFact()
                    if (restricts(old, change.source.toFact())) restricted += change.source.id
                    db.sources().upsert(listOf(change.source.toEntity()))
                }
                is SyncChange.Removed -> when (change.entityType) {
                    "concern" -> db.concerns().delete(change.entityId)
                    "source" -> {
                        val old = if (held != null) held[change.entityId] else db.sources().get(change.entityId)?.toFact()
                        if (restricts(old, null)) restricted += change.entityId
                        db.sources().delete(change.entityId)
                    }
                }
                is SyncChange.Ignored -> Unit
            }
        }
        // Cached text survives only for an unchanged source; deletion and revision changes drop it here.
        db.sourceTexts().pruneStale()
        return restricted
    }

    override suspend fun applyChanges(changes: List<SyncChange>, throughSeq: Long, nowMs: Long) = db.withTransaction {
        val restricted = write(changes)
        // Stored answers that rest on an email that just became unsafe are dropped in the same transaction.
        chat.dropRestrictedAnswers(restricted)
        db.syncState().put(SyncStateEntity(throughSeq = throughSeq, needsBootstrap = false, lastSyncedAt = nowMs))
    }

    override suspend fun replaceWithSnapshot(changes: List<SyncChange>, snapshotSeq: Long, nowMs: Long) = db.withTransaction {
        val held = db.sources().all().associate { it.id to it.toFact() }
        db.concerns().clear()
        db.sources().clear()
        val restricted = write(changes, held)
        // An email the snapshot no longer lists was deleted while this phone was not looking.
        val listed = changes.filterIsInstance<SyncChange.SourceChanged>().map { it.source.id }.toSet()
        for ((id, fact) in held) if (id !in listed && restricts(fact, null)) restricted += id
        chat.dropRestrictedAnswers(restricted)
        db.syncState().put(SyncStateEntity(throughSeq = snapshotSeq, needsBootstrap = false, lastSyncedAt = nowMs))
    }

    override suspend fun markNeedsBootstrap() = db.withTransaction {
        val current = db.syncState().get()
        db.syncState().put(SyncStateEntity(throughSeq = current?.throughSeq ?: 0, needsBootstrap = true, lastSyncedAt = current?.lastSyncedAt))
    }

    override suspend fun mutations(): List<QueuedMutation> = db.mutations().all().map { it.toQueued() }

    override suspend fun enqueue(key: String, concernId: String, action: ConcernAction, nowMs: Long): PendingMutation? = db.withTransaction {
        val revision = db.concerns().revision(concernId) ?: return@withTransaction null
        val expected = revision + db.mutations().openCount(concernId)
        val (kind, args) = ActionCodec.encode(action)
        db.mutations().insert(PendingMutationEntity(key, concernId, expected, kind, args, nowMs, 0, MutationState.QUEUED.name, nowMs, null, null))
        PendingMutation(key, concernId, expected, action, nowMs)
    }

    override suspend fun record(key: String, decision: OutboxDecision, nowMs: Long) = db.withTransaction {
        val row = db.mutations().get(key) ?: return@withTransaction
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
        db.mutations().delete(key)
        val revision = db.concerns().revision(row.concernId) ?: return@withTransaction null
        val expected = revision + db.mutations().openCount(row.concernId)
        db.mutations().insert(row.copy(idempotencyKey = newKey, expectedRevision = expected, createdAt = nowMs, attempts = 0, state = MutationState.QUEUED.name, nextAttemptAt = nowMs, errorCode = null, currentRevision = null))
        PendingMutation(newKey, row.concernId, expected, ActionCodec.decode(row.kind, row.argsJson), nowMs)
    }
}
