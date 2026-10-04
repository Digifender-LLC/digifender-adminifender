package com.digifender.adminifender.core.data.repository

import com.digifender.adminifender.core.data.sync.LocalStore
import com.digifender.adminifender.core.data.sync.QueuedMutation
import com.digifender.adminifender.core.model.Concern
import com.digifender.adminifender.core.model.ConcernAction
import com.digifender.adminifender.core.model.Transition
import com.digifender.adminifender.core.model.applyOptimistic
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Read side of the local cache as flows; Room provides it in the app and tests use in-memory flows. */
interface CacheSource {
    fun concerns(): Flow<List<Concern>>

    fun mutations(): Flow<List<QueuedMutation>>

    fun syncState(): Flow<Pair<Long?, Boolean>>
}

/** Starts background sync; the app backs it with WorkManager. */
interface SyncScheduler {
    fun requestNow()

    fun schedulePeriodic()

    fun cancelAll()
}

sealed interface ActResult {
    /** Queued on the phone; the server has not confirmed it yet. */
    data object Queued : ActResult

    data object NotAvailable : ActResult

    data object SnoozeInPast : ActResult

    data object NotCached : ActResult
}

/**
 * Concerns for the UI (lld.md#android): reads come only from the cache, and owner actions go through the persistent
 * queue, never straight to HTTP. Nothing here reports a server result the server has not given.
 */
class ConcernRepository(
    private val cache: CacheSource,
    private val store: LocalStore,
    private val scheduler: SyncScheduler,
    private val clock: () -> Long,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
) {
    private fun views(): Flow<List<ConcernView>> = combine(cache.concerns(), cache.mutations()) { concerns, mutations -> overlay(concerns, mutations, clock()) }

    fun inbox(filter: InboxFilter): Flow<List<ConcernView>> = views().map { inbox(it, filter) }

    fun concern(id: String): Flow<ConcernView?> = views().map { views -> views.firstOrNull { it.server.id == id } }

    fun freshness(): Flow<Freshness> = combine(cache.syncState(), cache.mutations()) { (last, bootstrap), mutations -> freshness(last, bootstrap, mutations) }

    /** Owner-requested refresh; the result shows up through [freshness], not as a return value. */
    fun refresh() = scheduler.requestNow()

    /** Checks the control against what the owner sees, then queues it with a new idempotency key. */
    suspend fun act(id: String, action: ConcernAction): ActResult {
        val now = clock()
        val view = views().first().firstOrNull { it.server.id == id } ?: return ActResult.NotCached
        when (applyOptimistic(view.shown, action, now)) {
            is Transition.NotAvailable -> return ActResult.NotAvailable
            Transition.SnoozeInPast -> return ActResult.SnoozeInPast
            is Transition.Applied -> Unit
        }
        store.enqueue(newKey(), id, action, now) ?: return ActResult.NotCached
        scheduler.requestNow()
        return ActResult.Queued
    }

    /** The owner's explicit resolution of a conflict or failure. */
    suspend fun discard(key: String) = store.discard(key)

    suspend fun reapply(key: String) {
        store.reapply(key, newKey(), clock())
        scheduler.requestNow()
    }
}
