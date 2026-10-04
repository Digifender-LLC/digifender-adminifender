package com.digifender.adminifender.core.data.sync

import com.digifender.adminifender.core.data.network.SyncChange
import com.digifender.adminifender.core.model.ConcernAction
import com.digifender.adminifender.core.model.OutboxDecision
import com.digifender.adminifender.core.model.PendingMutation

data class SyncState(val throughSeq: Long, val needsBootstrap: Boolean, val lastSyncedAtMs: Long?)

/** Queue state of one offline change, as the UI shows it. */
enum class MutationState { QUEUED, CONFLICT, FAILED, HELD }

data class QueuedMutation(
    val mutation: PendingMutation,
    val state: MutationState,
    val nextAttemptAtMs: Long,
    val errorCode: String?,
    val currentRevision: Int?,
)

/**
 * The phone's durable store. Every method is one transaction: a page of server changes and the cursor that
 * covers it commit together, so the cursor never runs ahead of stored data (lld.md#sync).
 */
interface LocalStore {
    suspend fun syncState(): SyncState

    suspend fun applyChanges(changes: List<SyncChange>, throughSeq: Long, nowMs: Long)

    /**
     * Replaces cached server state with a complete snapshot. Queued changes are kept: they are the owner's intent
     * and are resolved by sending them, not by discarding them.
     */
    suspend fun replaceWithSnapshot(changes: List<SyncChange>, snapshotSeq: Long, nowMs: Long)

    suspend fun markNeedsBootstrap()

    /** Every queued change in creation order, including conflicts and failures awaiting the owner. */
    suspend fun mutations(): List<QueuedMutation>

    /** Stores a new change; the expected revision is predicted from the server copy plus changes already queued. */
    suspend fun enqueue(key: String, concernId: String, action: ConcernAction, nowMs: Long): PendingMutation?

    suspend fun record(key: String, decision: OutboxDecision, nowMs: Long)

    /** Owner's explicit choice for a conflict: drop it, or reapply it on top of the current server revision. */
    suspend fun discard(key: String)

    suspend fun reapply(key: String, newKey: String, nowMs: Long): PendingMutation?
}
