package com.digifender.adminifender.core.data.sync

import com.digifender.adminifender.core.data.network.AdminifenderApi
import com.digifender.adminifender.core.data.network.ReadResult
import com.digifender.adminifender.core.data.network.SyncChange
import com.digifender.adminifender.core.data.network.toChange
import com.digifender.adminifender.core.model.OutboxDecision
import com.digifender.adminifender.core.model.decide

/** Result of one sync pass, for WorkManager scheduling and the diagnostics screen. */
sealed interface SyncOutcome {
    data class Synced(val throughSeq: Long, val sent: Int, val conflicts: Int) : SyncOutcome
    data object SignInRequired : SyncOutcome
    data class Unavailable(val code: String) : SyncOutcome
}

const val PAGE_SIZE = 200
private const val MAX_PAGES_PER_PASS = 50

/**
 * One sync pass: send queued owner changes first (each with its fixed idempotency key, in per-concern order),
 * then pull the change feed page by page. A page enters the store only when it parsed completely, and the cursor
 * moves with it. An expired cursor triggers a full snapshot that keeps queued changes.
 */
class SyncEngine(private val api: AdminifenderApi, private val store: LocalStore, private val clock: () -> Long) {
    suspend fun syncOnce(): SyncOutcome {
        val flushed = flush()
        if (flushed is FlushResult.SignIn) return SyncOutcome.SignInRequired
        val pulled = pull()
        val sent = (flushed as? FlushResult.Done)?.sent ?: 0
        val conflicts = (flushed as? FlushResult.Done)?.conflicts ?: 0
        return when (pulled) {
            is PullResult.Done -> SyncOutcome.Synced(pulled.throughSeq, sent, conflicts)
            PullResult.SignIn -> SyncOutcome.SignInRequired
            is PullResult.Failed -> SyncOutcome.Unavailable(pulled.code)
        }
    }

    private sealed interface FlushResult {
        data class Done(val sent: Int, val conflicts: Int) : FlushResult
        data object SignIn : FlushResult
    }

    private suspend fun flush(): FlushResult {
        var sent = 0
        var conflicts = 0
        val blocked = mutableSetOf<String>()
        val now = clock()
        for (queued in store.mutations()) {
            val m = queued.mutation
            // Changes to one concern go out in order: anything unsettled, backing off or awaiting the owner holds
            // back the later ones.
            val sendable = (queued.state == MutationState.QUEUED || queued.state == MutationState.HELD) && queued.nextAttemptAtMs <= now
            if (m.concernId in blocked || !sendable) {
                blocked += m.concernId
                continue
            }
            val decision = decide(m, api.concernAction(m.concernId, m.idempotencyKey, m.expectedRevision, m.action))
            store.record(m.idempotencyKey, decision, clock())
            when (decision) {
                OutboxDecision.Remove -> sent += 1
                OutboxDecision.HoldForSignIn -> return FlushResult.SignIn
                is OutboxDecision.Conflict -> {
                    conflicts += 1
                    blocked += m.concernId
                }
                else -> blocked += m.concernId
            }
        }
        return FlushResult.Done(sent, conflicts)
    }

    private sealed interface PullResult {
        data class Done(val throughSeq: Long) : PullResult
        data object SignIn : PullResult
        data class Failed(val code: String) : PullResult
    }

    private suspend fun pull(): PullResult {
        if (store.syncState().needsBootstrap) return bootstrap()
        repeat(MAX_PAGES_PER_PASS) {
            val after = store.syncState().throughSeq
            when (val page = api.changes(after, PAGE_SIZE)) {
                is ReadResult.Ok -> {
                    val changes = try {
                        page.value.changes.map { it.toChange() }
                    } catch (_: IllegalArgumentException) {
                        return PullResult.Failed("malformed_change")
                    }
                    store.applyChanges(changes, page.value.throughSeq, clock())
                    if (!page.value.hasMore) return PullResult.Done(page.value.throughSeq)
                }
                ReadResult.CursorExpired -> {
                    store.markNeedsBootstrap()
                    return bootstrap()
                }
                ReadResult.SessionExpired -> return PullResult.SignIn
                is ReadResult.Unavailable -> return PullResult.Failed(page.code)
            }
        }
        return PullResult.Done(store.syncState().throughSeq)
    }

    private suspend fun bootstrap(): PullResult {
        val collected = mutableListOf<SyncChange>()
        var cursor: String? = null
        var snapshotSeq: Long
        do {
            when (val page = api.bootstrap(cursor, PAGE_SIZE)) {
                is ReadResult.Ok -> {
                    snapshotSeq = page.value.snapshotSeq
                    try {
                        page.value.entities.mapTo(collected) { it.toChange(snapshotSeq) }
                    } catch (_: IllegalArgumentException) {
                        return PullResult.Failed("malformed_change")
                    }
                    cursor = page.value.nextCursor
                }
                // A snapshot that expired mid-way is restarted on the next pass; nothing partial is stored.
                ReadResult.CursorExpired -> return PullResult.Failed("snapshot_expired")
                ReadResult.SessionExpired -> return PullResult.SignIn
                is ReadResult.Unavailable -> return PullResult.Failed(page.code)
            }
        } while (cursor != null)
        store.replaceWithSnapshot(collected, snapshotSeq, clock())
        return pull()
    }
}
