package com.digifender.adminifender.core.data

import com.digifender.adminifender.core.data.network.SourceSummary
import com.digifender.adminifender.core.data.network.SyncChange
import com.digifender.adminifender.core.data.sync.LocalStore
import com.digifender.adminifender.core.data.sync.MutationState
import com.digifender.adminifender.core.data.sync.QueuedMutation
import com.digifender.adminifender.core.data.sync.SyncState
import com.digifender.adminifender.core.model.Concern
import com.digifender.adminifender.core.model.ConcernAction
import com.digifender.adminifender.core.model.OutboxDecision
import com.digifender.adminifender.core.model.PendingMutation

/** Test double with the same transaction semantics as RoomLocalStore: each call applies fully or not at all. */
class InMemoryStore : LocalStore {
    val concerns = linkedMapOf<String, Concern>()
    val sources = linkedMapOf<String, SourceSummary>()
    val queue = mutableListOf<QueuedMutation>()
    var state = SyncState(0, needsBootstrap = true, lastSyncedAtMs = null)
    var failNextApply = false

    override suspend fun syncState() = state

    private fun write(changes: List<SyncChange>) {
        for (c in changes) when (c) {
            is SyncChange.ConcernChanged -> concerns[c.concern.id] = c.concern
            is SyncChange.SourceChanged -> sources[c.source.id] = c.source
            is SyncChange.Removed -> if (c.entityType == "concern") concerns.remove(c.entityId) else sources.remove(c.entityId)
            is SyncChange.Ignored -> Unit
        }
    }

    override suspend fun applyChanges(changes: List<SyncChange>, throughSeq: Long, nowMs: Long) {
        if (failNextApply) {
            failNextApply = false
            throw IllegalStateException("disk full")
        }
        write(changes)
        state = SyncState(throughSeq, false, nowMs)
    }

    override suspend fun replaceWithSnapshot(changes: List<SyncChange>, snapshotSeq: Long, nowMs: Long) {
        concerns.clear()
        sources.clear()
        write(changes)
        state = SyncState(snapshotSeq, false, nowMs)
    }

    override suspend fun markNeedsBootstrap() {
        state = state.copy(needsBootstrap = true)
    }

    override suspend fun mutations() = queue.toList()

    private fun open(concernId: String) = queue.count { it.mutation.concernId == concernId && (it.state == MutationState.QUEUED || it.state == MutationState.HELD) }

    override suspend fun enqueue(key: String, concernId: String, action: ConcernAction, nowMs: Long): PendingMutation? {
        val revision = concerns[concernId]?.revision ?: return null
        val m = PendingMutation(key, concernId, revision + open(concernId), action, nowMs)
        queue += QueuedMutation(m, MutationState.QUEUED, nowMs, null, null)
        return m
    }

    override suspend fun record(key: String, decision: OutboxDecision, nowMs: Long) {
        val i = queue.indexOfFirst { it.mutation.idempotencyKey == key }
        if (i < 0) return
        val q = queue[i]
        when (decision) {
            OutboxDecision.Remove -> queue.removeAt(i)
            is OutboxDecision.Retry -> queue[i] = q.copy(mutation = decision.next, state = MutationState.QUEUED, nextAttemptAtMs = nowMs + decision.delayMs)
            is OutboxDecision.Conflict -> queue[i] = q.copy(state = MutationState.CONFLICT, errorCode = "stale_revision", currentRevision = decision.currentRevision)
            is OutboxDecision.Failed -> queue[i] = q.copy(state = MutationState.FAILED, errorCode = decision.code)
            OutboxDecision.HoldForSignIn -> queue[i] = q.copy(state = MutationState.HELD, nextAttemptAtMs = nowMs)
        }
    }

    override suspend fun discard(key: String) {
        queue.removeAll { it.mutation.idempotencyKey == key }
    }

    override suspend fun reapply(key: String, newKey: String, nowMs: Long): PendingMutation? {
        val q = queue.firstOrNull { it.mutation.idempotencyKey == key } ?: return null
        discard(key)
        return enqueue(newKey, q.mutation.concernId, q.mutation.action, nowMs)
    }
}
